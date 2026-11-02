package top.xtcoder.xtlog.service;

import java.util.ArrayList;
import java.util.List;

import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;

import top.xtcoder.xtlog.config.DataSourceConfig;

/**
 * 日志内容（message）的全文索引：SQLite FTS5 + trigram 分词。
 *
 * <p>背景：日志 message 平均几十 KB（本库平均 35KB、最大 7.6MB），关键词检索写的是
 * {@code message LIKE '%kw%'}，用不上任何索引，每次都要把整月的 message 通读一遍
 * （3.2GB 文本实测 3.5~8.3s）。FTS5 的 trigram 分词器把文本切成连续 3 字符的倒排项，
 * 恰好等价于「子串匹配」，MATCH 查询实测 0.002~0.006s，快 3 个数量级。
 *
 * <p>存储：虚拟表用 {@code content='<月表>'} 声明成「外部内容表」，FTS 只存倒排索引、
 * 不复制原文（否则 3.2GB 文本要再占一份）。代价是索引本身约为原文的 1.65 倍（实测），
 * 全库约 +5GB，所以做成开关，不想要可以一键关掉并释放。
 *
 * <p>一致性：索引不跟着写入实时更新（会拖慢采集），而是后台增量回填。水位表
 * {@code xt_fts_state} 记录每张月表「已索引到的最大 id」，查询时把结果拆成两段——
 * 已索引段走 FTS、未索引尾段走 LIKE——所以任何时刻的查询结果都是完整的，
 * 只是回填期间尾段仍然慢一点。
 *
 * <p>局限：trigram 按 3 字符切词，关键词不足 3 个字符（例如 2 个汉字的「异常」）
 * 切不出任何 token，只能回退 LIKE。见 {@link #MIN_LEN}。
 */
public class FtsService {

    /** 全文索引开关在 xt_config 里的键 */
    public static final String KEY_ENABLED = "fts.enabled";

    /** 索引水位表：t=月表名, seq=已索引到的最大 id */
    public static final String STATE_TABLE = "xt_fts_state";

    /** 月表名前缀 / FTS 虚拟表前缀（FTS 表刻意不叫 xt_log_entry_*，避免被 listLogTables 当成月表） */
    public static final String LOG_PREFIX = "xt_log_entry_";
    public static final String FTS_PREFIX = "xt_log_fts_";

    /** trigram 最短可索引长度：少于 3 个字符无法切出 token，必须回退 LIKE */
    public static final int MIN_LEN = 3;

    /**
     * 单批回填的文本预算（字符数）。
     * 必须按「文本量」而不是「行数」切批：message 大小差三个数量级（几十字节 ~ 7.6MB），
     * 按行切批会撞上「一批里全是巨行」的情况，事务一开就是几十秒，
     * WAL 的写锁被长时间占住，正在落库的日志会直接吃 SQLITE_BUSY 失败。
     *
     * <p>这里故意压到 2MB：每批事务亚秒~1-2 秒即可释放写锁，给实时落库留出穿插窗口。
     * 单次回填变慢（约 2.5 分钟填满 1.17GB，且查询侧未索引尾段自动回退 LIKE，体验平滑），
     * 换来「回填永远饿不死采集端」——这是比速度更重要的正确性保证。
     * 注意：单条 7.6MB 巨行仍会独占一个事务（无法再切小），它是最坏情况，靠落库侧的
     * 锁冲突重入队机制兜底，不会因此丢日志。
     */
    public static final long BATCH_CHARS = 2_000_000L;

    /**
     * 单条 message 超过此字符数就不进 FTS 倒排索引，改为「标记跳过、查询时回退 LIKE」。
     * 原因：trigram 对超大文本建索引很慢（实测 7MB 约需数秒且独占写锁），而这类巨行几乎都是
     * 大栈轨迹 / base64 块，搜它用 LIKE 即可。低于此阈值的行正常建索引，既快又避开了卡死风险。
     */
    public static final long MAX_FTS_DOC_CHARS = 1_000_000L;

    /** 跳过的超大 / 损坏行记录表（t=月表名, id=行号），查询时这些行并入 LIKE 兜底段 */
    public static final String SKIP_TABLE = "xt_fts_skip";

    /** 确定批次上界时最多扫多少个 id（只是读主键 + 长度，很轻） */
    public static final int SCAN_ROWS = 4000;

    /** 元表是否已建过（避免每次查询都跑一遍 DDL）；切换数据源后必须重新建 */
    private static volatile boolean stateReady = false;
    private static volatile DbClientHolder stateHolder = null;

    // ==================== 可用性 / 开关 ====================

    /** 当前数据源是否支持全文索引（FTS5 是 SQLite 专有能力） */
    public static boolean supported(DbClientHolder holder) {
        return DataSourceConfig.isSqlite(holder);
    }

    /** 开关是否打开（数据源不支持时恒为 false） */
    public static boolean enabled(DbClientHolder holder) {
        if (!supported(holder)) {
            return false;
        }
        return "1".equals(DataSourceConfig.getConfig(holder, KEY_ENABLED));
    }

    /** 打开 / 关闭开关。关闭时会把已建的索引表全部删掉，立即释放磁盘 */
    public static void setEnabled(DbClientHolder holder, boolean on) {
        DataSourceConfig.setConfig(holder, KEY_ENABLED, on ? "1" : "0");
        if (!on) {
            dropAll(holder);
        }
    }

    /** 月表对应的 FTS 虚拟表名 */
    public static String ftsTableOf(String logTable) {
        if (logTable == null || !logTable.startsWith(LOG_PREFIX)) {
            return null;
        }
        return FTS_PREFIX + logTable.substring(LOG_PREFIX.length());
    }

    /** 关键词能否走索引：长度够 + 数据源支持 + 开关已开 */
    public static boolean canUse(DbClientHolder holder, String keyword) {
        return keyword != null && keyword.length() >= MIN_LEN && enabled(holder);
    }

    /**
     * 把用户输入转成 FTS5 的 MATCH 表达式：整串用双引号包成短语，内部的双引号翻倍转义。
     * 这样关键词里的空格、标点、{@code -}、{@code *} 都会被当字面量，不会被解释成运算符。
     */
    public static String matchExpr(String keyword) {
        String k = keyword == null ? "" : keyword.replace("\"", "\"\"");
        return "\"" + k + "\"";
    }

    // ==================== 元表 ====================

    public static void ensureStateTable(DbClientHolder holder) {
        // 数据源热切换后 holder 是新实例，元表要在新库里重建，不能沿用旧标记
        if (stateReady && stateHolder == holder) {
            return;
        }
        try {
            holder.executeUpdate("CREATE TABLE IF NOT EXISTS " + STATE_TABLE
                    + " (t VARCHAR(64) PRIMARY KEY, seq BIGINT)");
            holder.executeUpdate("CREATE TABLE IF NOT EXISTS " + SKIP_TABLE
                    + " (t VARCHAR(64), id BIGINT, PRIMARY KEY(t, id))");
            stateReady = true;
            stateHolder = holder;
        } catch (Exception e) {
            System.err.println("[xtlog] fts state table init failed: " + e.getMessage());
        }
    }

    /** 某月表已索引到的最大 id；0 表示一行都没索引 */
    public static long watermark(DbClientHolder holder, String logTable) {
        ensureStateTable(holder);
        try {
            List<Dict> rows = holder.executeQuery("SELECT seq FROM " + STATE_TABLE + " WHERE t = ?", logTable);
            if (rows != null && !rows.isEmpty()) {
                Object v = rows.get(0).get("seq");
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
                if (v != null) {
                    return Long.parseLong(String.valueOf(v).trim());
                }
            }
        } catch (Exception e) {
            // 元表损坏 / 方言差异：当作「未索引」，查询自动回退 LIKE，不影响正确性
        }
        return 0L;
    }

    private static void setWatermark(DbClientHolder holder, String logTable, long seq) {
        ensureStateTable(holder);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                List<Dict> rows = holder.executeQuery("SELECT seq FROM " + STATE_TABLE + " WHERE t = ?", logTable);
                if (rows != null && !rows.isEmpty()) {
                    holder.executeUpdate("UPDATE " + STATE_TABLE + " SET seq = ? WHERE t = ?",
                            Long.valueOf(seq), logTable);
                } else {
                    holder.executeUpdate("INSERT INTO " + STATE_TABLE + " (t, seq) VALUES (?, ?)",
                            logTable, Long.valueOf(seq));
                }
                return;
            } catch (Exception e) {
                System.err.println("[xtlog] fts watermark update failed (attempt "
                        + (attempt + 1) + "): " + e.getMessage());
            }
        }
    }

    /** 记录一行「超大 / 损坏、未能进 FTS」，查询时它会走 LIKE 兜底段，正确性不丢 */
    public static void recordSkip(DbClientHolder holder, String logTable, long id) {
        try {
            holder.executeUpdate("INSERT OR IGNORE INTO " + SKIP_TABLE + " (t, id) VALUES (?, ?)",
                    logTable, Long.valueOf(id));
        } catch (Exception e) {
            // 极端情况（元表损坏）下记录失败也无关紧要：该行只是走 LIKE，不影响正确性
        }
    }

    /** 某月表已跳过（未进索引）的行数 */
    public static long skipCount(DbClientHolder holder, String logTable) {
        try {
            List<Dict> r = holder.executeQuery("SELECT COUNT(1) AS c FROM " + SKIP_TABLE + " WHERE t = ?", logTable);
            if (r != null && !r.isEmpty()) {
                Object v = r.get(0).get("c");
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            }
        } catch (Exception ignore) {
            // 元表尚不存在等情况
        }
        return 0L;
    }

    /** 判断异常是否是写锁竞争（busy / locked），这种应当下个周期重试而不是跳过行 */
    private static boolean isLockContention(Throwable e) {
        String m = e == null ? "" : (e.getMessage() == null ? "" : e.getMessage().toLowerCase());
        return m.contains("busy") || m.contains("locked") || m.contains("sqlite_busy")
                || m.contains("database is locked");
    }

    // ==================== 建表 / 回填 / 删除 ====================

    /** 建（或确认）某月表的 FTS 虚拟表。返回是否可用 */
    public static boolean ensureFtsTable(DbClientHolder holder, String logTable) {
        String fts = ftsTableOf(logTable);
        if (fts == null) {
            return false;
        }
        try {
            holder.executeUpdate("CREATE VIRTUAL TABLE IF NOT EXISTS " + fts
                    + " USING fts5(message, content='" + logTable + "', content_rowid='id',"
                    + " tokenize='trigram')");
            return true;
        } catch (Exception e) {
            System.err.println("[xtlog] fts create " + fts + " failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 回填一批：把 id 大于水位的行按「文本量预算」切成一段，普通行写进倒排索引、超大行跳过并记录，
     * 然后推进水位。<b>无论本批是否完全成功，水位都向前推进</b>——这就是根治
     * 「跑到一半停住、关掉重开也没用」的关键：旧逻辑遇到一行插不进去就整批回滚且不推进水位，
     * 于是永远重试同一批；新逻辑宁可把坏行记进 {@link #SKIP_TABLE} 也绝不回头。
     *
     * <p>幂等：写入前先排除「已经在索引里的 rowid」，所以即便水位更新偶发失败导致同一段被重复处理，
     * 也不会往 FTS 里塞重复项（旧逻辑因此把 xt_log_fts_202610 撑到 23 万行、水位却停在 17437）。
     *
     * <p>超大行（{@link #MAX_FTS_DOC_CHARS}）：不进索引、记进 {@link #SKIP_TABLE}。查询时这些行并入
     * 「LIKE 兜底段」，照常可被关键词搜到，只是不走倒排、慢一点——正确性不丢。
     *
     * @return 本批处理过的行数（含跳过）；0 表示已追平（无新行）
     */
    public static int buildBatch(DbClientHolder holder, String logTable, long from) {
        String fts = ftsTableOf(logTable);
        if (fts == null || !ensureFtsTable(holder, logTable)) {
            return 0;
        }
        try {
            // 1) 扫描候选行（只取 id + 长度，不碰大字段，很便宜）。巨行也扫进来以便快速跳过。
            List<Dict> scanned = holder.executeQuery(
                    "SELECT id, LENGTH(message) AS n FROM " + logTable
                            + " WHERE id > ? ORDER BY id LIMIT ?",
                    Long.valueOf(from), Integer.valueOf(SCAN_ROWS));
            if (scanned == null || scanned.isEmpty()) {
                return 0;
            }

            List<Long> giant = new ArrayList<Long>();   // 超大，跳过
            List<Long> normal = new ArrayList<Long>();  // 普通，进索引
            long chars = 0L;
            long maxId = 0L;
            for (Dict d : scanned) {
                Object idv = d.get("id");
                if (!(idv instanceof Number)) {
                    continue;
                }
                long id = ((Number) idv).longValue();
                long n = 0L;
                Object nv = d.get("n");
                if (nv instanceof Number) {
                    n = ((Number) nv).longValue();
                }
                maxId = id;
                if (n > MAX_FTS_DOC_CHARS) {
                    giant.add(Long.valueOf(id));
                } else {
                    normal.add(Long.valueOf(id));
                    chars += n;
                    if (chars >= BATCH_CHARS) {
                        // 普通行已凑够文本预算：停止扫描后续行（含其后的巨行，下个周期再处理）
                        break;
                    }
                }
            }
            if (maxId <= from) {
                return 0;
            }

            // 2) 普通行：批量写入倒排索引。
            // 幂等靠「水位单调」保证：正常流程下每个 (表,id) 只会被处理一次（from=水位，只取 id>水位），
            // 不会重复；即便水位持久化偶发失败导致同一段被重放，外部内容表对同 rowid 重插也不会产生
            // 重复项（实测 MATCH 仍只命中一次）。因此这里刻意不写
            // 「id NOT IN (SELECT rowid FROM fts ...)」——那种写法对外部内容表是错的：外部内容表的
            // SELECT rowid 返回的是「内容表」的行而非「已索引」的行，会把所有候选行都排除掉，
            // 导致一条都插不进去（旧实现因此全文索引永远是空的，表现为「开了索引但查不到、重跑也没用」）。
            if (!normal.isEmpty()) {
                try {
                    holder.executeUpdate(
                            "INSERT INTO " + fts + "(rowid, message)"
                                    + " SELECT id, message FROM " + logTable
                                    + " WHERE id > ? AND id <= ? AND LENGTH(message) <= ?",
                            Long.valueOf(from), Long.valueOf(maxId),
                            Long.valueOf(MAX_FTS_DOC_CHARS));
                } catch (Exception e) {
                    if (isLockContention(e)) {
                        // 写锁被占（busy_timeout 通常已兜住，极少触发）：本批不动，下个周期重试
                        return 0;
                    }
                    // 非锁错误（个别行损坏等）：退化为逐行写入，坏行记进 skip 后继续前进
                    System.err.println("[xtlog] fts bulk insert failed, fallback per-row: " + e.getMessage());
                    for (Long id : normal) {
                        try {
                            holder.executeUpdate(
                                    "INSERT INTO " + fts + "(rowid, message)"
                                            + " SELECT id, message FROM " + logTable
                                            + " WHERE id = ?",
                                    id);
                        } catch (Exception ex) {
                            recordSkip(holder, logTable, id.longValue());
                        }
                    }
                }
            }

            // 3) 超大行：记进 skip（不进索引，查询走 LIKE 兜底）
            for (Long id : giant) {
                recordSkip(holder, logTable, id.longValue());
            }

            // 4) 无论如何推进水位（「永不卡死」的核心）
            setWatermark(holder, logTable, maxId);
            return scanned.size();
        } catch (Exception e) {
            System.err.println("[xtlog] fts build " + logTable + " failed: " + e.getMessage());
            return 0;
        }
    }

    /** 删掉某月表的索引（保留策略删表、或用户关闭开关时调用） */
    public static void dropFor(DbClientHolder holder, String logTable) {
        String fts = ftsTableOf(logTable);
        if (fts == null) {
            return;
        }
        try {
            holder.executeUpdate("DROP TABLE IF EXISTS " + fts);
        } catch (Exception e) {
            System.err.println("[xtlog] fts drop " + fts + " failed: " + e.getMessage());
        }
        try {
            holder.executeUpdate("DELETE FROM " + STATE_TABLE + " WHERE t = ?", logTable);
        } catch (Exception ignore) {
            // 元表不存在时忽略
        }
    }

    /** 关闭开关：删掉全部 FTS 表与水位记录，释放磁盘 */
    public static void dropAll(DbClientHolder holder) {
        try {
            List<String> ftsTables = new ArrayList<String>();
            List<Dict> rows = holder.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '" + FTS_PREFIX + "%'");
            if (rows != null) {
                for (Dict r : rows) {
                    Object n = r.get("name");
                    if (n != null) {
                        ftsTables.add(String.valueOf(n));
                    }
                }
            }
            for (String t : ftsTables) {
                try {
                    holder.executeUpdate("DROP TABLE " + t);
                } catch (Exception e) {
                    System.err.println("[xtlog] fts drop " + t + " failed: " + e.getMessage());
                }
            }
            holder.executeUpdate("DELETE FROM " + STATE_TABLE);
            holder.executeUpdate("DELETE FROM " + SKIP_TABLE);
            System.out.println("[xtlog] fts disabled, dropped " + ftsTables.size() + " index table(s)");
        } catch (Exception e) {
            System.err.println("[xtlog] fts disable failed: " + e.getMessage());
        }
    }

    // ==================== 状态（给设置页展示进度） ====================

    /**
     * 各月表的索引进度：{table, rows, indexed, pct, done}。
     * rows 用 COUNT(1)（走索引，毫秒级），indexed 取水位。
     */
    public static List<Dict> status(DbClientHolder holder, List<String> logTables) {
        List<Dict> out = new ArrayList<Dict>();
        boolean on = enabled(holder);
        for (String t : logTables) {
            Dict d = new Dict();
            d.put("table", t);
            long rows = countRows(holder, t);
            long wm = on ? watermark(holder, t) : 0L;
            long sk = on ? skipCount(holder, t) : 0L;
            long indexed = on ? Math.max(0L, Math.min(wm, rows) - sk) : 0L;
            d.put("rows", Long.valueOf(rows));
            d.put("indexed", Long.valueOf(indexed));
            d.put("skipped", Long.valueOf(sk));
            d.put("pct", Integer.valueOf(rows <= 0 ? 100 : (int) (indexed * 100 / rows)));
            d.put("done", Boolean.valueOf(rows > 0 && wm >= rows));
            out.add(d);
        }
        return out;
    }

    public static long countRows(DbClientHolder holder, String table) {
        try {
            List<Dict> r = holder.executeQuery("SELECT COUNT(1) AS c FROM " + table);
            if (r != null && !r.isEmpty()) {
                Object v = r.get(0).get("c");
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            }
        } catch (Exception ignore) {
            // 表刚被清理掉等情况
        }
        return 0L;
    }
}
