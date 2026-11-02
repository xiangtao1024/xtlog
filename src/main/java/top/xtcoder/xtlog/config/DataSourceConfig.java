package top.xtcoder.xtlog.config;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import top.xtcoder.xtboot.context.annotation.Bean;
import top.xtcoder.xtboot.context.annotation.Configuration;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClient;
import top.xtcoder.xtboot.orm.DbClientHolder;

import top.xtcoder.xtlog.service.FtsService;

/**
 * 数据源装配：默认 SQLite（WAL + busy_timeout），并初始化所有表结构。
 * 通过 DbClientHolder 暴露为单例 Bean，框架优雅停机时会自动关闭其连接池。
 */
@Configuration
public class DataSourceConfig {

    /** 数据源持久化文件名（落盘在 dataDir() 下） */
    public static final String DS_FILE = "datasource.properties";

    /**
     * 数据根目录。优先级：系统属性 xtlog.data.dir > 环境变量 XTLOG_DATA_DIR > 当前工作目录下的 data。
     * 部署时建议显式指定环境变量，避免依赖进程工作目录。
     */
    public static String dataDir() {
        String d = System.getProperty("xtlog.data.dir");
        if (d == null || d.trim().length() == 0) {
            d = System.getenv("XTLOG_DATA_DIR");
        }
        if (d == null || d.trim().length() == 0) {
            d = "data";
        }
        return d.trim();
    }

    /**
     * 默认 SQLite 库路径（相对 dataDir()）。
     * PRAGMA 通过 JDBC URL 参数下发，由驱动对「每个新建连接」生效。
     * 注意：synchronous 是连接级设置，必须写在 URL 里，否则只对单条连接有效。
     */
    public static String sqliteUrl() {
        // busy_timeout 给到 10s：SQLite 只允许一个写者，后台落库 / FTS 回填 / 前台建应用都在抢同一把锁。
        // 10s 足以覆盖正常的短暂竞争（落库单组写事务亚秒级、FTS 每批 ~1-2s），又不至于像 60s 那样
        // 干等数十秒才把 SQLITE_BUSY 甩给用户。真正的兜底靠各写入方的「锁竞争重试」(DbBusyRetry) 与
        // 落库侧的重入队——任何竞争都只会造成延迟、不会丢日志或报错。
        return "jdbc:sqlite:" + dataDir() + "/xtlog.db"
                + "?busy_timeout=10000&journal_mode=WAL&synchronous=NORMAL";
    }

    @Bean
    public DbClientHolder dbClientHolder() {
        String[] saved = loadDatasource();
        String url = saved[0];
        String user = saved[1];
        String pwd = saved[2];
        if (url == null || url.length() == 0) {
            url = sqliteUrl();
        }
        new File(dataDir()).mkdirs();
        DbClient db = DbClient.create(url, user, pwd);
        DbClientHolder holder = new DbClientHolder(db);
        initSchema(holder);
        // 把当前数据源登记进 xt_datasource（首次启动才有实际写入），供界面「一键切回」
        ensureCurrentRecord(holder, url, user, pwd);
        // 启动即按保留策略清理一次过期月表（未配置保留月数时为空操作）
        try {
            List<String> dropped = applyRetention(holder);
            if (!dropped.isEmpty()) {
                System.out.println("[xtlog] retention: dropped " + dropped.size() + " expired table(s)");
            }
        } catch (Exception e) {
            System.err.println("[xtlog] retention cleanup failed: " + e.getMessage());
        }
        return holder;
    }

    /** 读取持久化的自定义数据源（安装向导切换过才会有），返回 {url,user,password} */
    public static String[] loadDatasource() {
        String[] r = new String[] { null, null, null };
        File f = new File(dataDir(), DS_FILE);
        if (!f.exists()) {
            return r;
        }
        java.io.InputStream in = null;
        try {
            java.util.Properties p = new java.util.Properties();
            in = new java.io.FileInputStream(f);
            p.load(new java.io.InputStreamReader(in, "UTF-8"));
            r[0] = p.getProperty("url");
            r[1] = p.getProperty("user");
            r[2] = p.getProperty("password");
        } catch (Exception ignore) {
            // 配置文件损坏时静默回退到默认 SQLite
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore2) {
                    // ignore
                }
            }
        }
        return r;
    }

    /**
     * 持久化自定义数据源到 dataDir()/datasource.properties，下次启动自动生效。
     * 传入 null url 表示删除该文件、恢复默认 SQLite。
     */
    public static void saveDatasource(String url, String user, String password) {
        File dir = new File(dataDir());
        dir.mkdirs();
        File f = new File(dir, DS_FILE);
        if (url == null || url.length() == 0) {
            f.delete();
            return;
        }
        java.io.OutputStream out = null;
        try {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("url", url);
            p.setProperty("user", user == null ? "" : user);
            p.setProperty("password", password == null ? "" : password);
            out = new java.io.FileOutputStream(f);
            p.store(new java.io.OutputStreamWriter(out, "UTF-8"), "xtlog-server datasource");
        } catch (Exception e) {
            System.err.println("[xtlog] save datasource failed: " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }
    }

    /** 全部表结构初始化（幂等） */
    public static void initSchema(DbClientHolder holder) {
        // 列定义统一交给 colDef()：它会按当前方言换成
        // MySQL 的 BIGINT AUTO_INCREMENT / LONGTEXT、PostgreSQL 的 BIGSERIAL / TIMESTAMP，
        // 否则切到 MySQL 时会栽在 SQLite 独有的 AUTOINCREMENT 语法上。
        createTable(holder, "category", new String[][] {
                { "id", "INTEGER", "1" },
                { "name", "VARCHAR(100)", "" },
                { "remark", "VARCHAR(500)", "" },
                { "create_time", "DATETIME", "" },
                { "update_time", "DATETIME", "" } });

        createTable(holder, "app", new String[][] {
                { "id", "INTEGER", "1" },
                { "category_id", "INTEGER", "" },
                { "name", "VARCHAR(100)", "" },
                { "token", "VARCHAR(64)", "" },
                { "source_type", "VARCHAR(20)", "" },
                { "file_paths", "TEXT", "" },
                { "remark", "VARCHAR(500)", "" },
                { "create_time", "DATETIME", "" },
                { "update_time", "DATETIME", "" } });

        // 迁移：CMD 采集源需要的命令字段（老库可能没有）
        addColumnIfMissing(holder, "app", "cmd", mapType(holder, "TEXT"));

        // 迁移：应用启用/停用开关。1=启用(默认) / 0=停用（停用后拒收 HTTP 推送、不启动采集器）。
        // 存量应用该列为 NULL，AppService 加载时按「启用」处理，故无需回填默认值。
        addColumnIfMissing(holder, "app", "enabled", mapType(holder, "INTEGER"));

        // 运行期配置（如日志保留月数）。独立小表，随库走，迁移时会一起被拷到新库
        ensureConfigTable(holder);

        // 迁移任务记录 + 数据源切换记录（同样随库走：迁移到新库时这两张表会被一起拷过去，
        // 所以在目标库里也能看到历史、并能一键切回源库）
        ensureMetaTables(holder);

        // 告警规则与事件表（v0.0.2 新增）
        ensureAlertTables(holder);

        // 当前月 + 上个月（跨月边界写入不会失败）
        ensureLogTable(holder, monthTable(System.currentTimeMillis()));
        ensureLogTable(holder, monthTable(System.currentTimeMillis() - 86400000L * 31));

        // 为已存在（可能很大的）月表补齐索引：老库升级、GB 级数据场景必做，否则查询全表扫描超时。
        // 首次启动会一次性建完，之后 IF NOT EXISTS 跳过。
        System.out.println("[xtlog] ensuring log table indexes ...");
        long t0 = System.currentTimeMillis();
        ensureLogIndexes(holder);
        System.out.println("[xtlog] log indexes ensured in " + (System.currentTimeMillis() - t0) + " ms");
    }

    // ==================== 方言适配的建表 ====================

    public static boolean isMySql(DbClientHolder holder) {
        try {
            return holder.get().getDialect() instanceof top.xtcoder.xtboot.orm.dialect.MySqlDialect;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isPg(DbClientHolder holder) {
        try {
            return holder.get().getDialect() instanceof top.xtcoder.xtboot.orm.dialect.PostgreSqlDialect;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 是否 SQLite。FTS5 全文索引是 SQLite 独有的能力（MySQL 的 FULLTEXT、PG 的 pg_trgm 是另一套语法），
     * 全文索引相关逻辑必须先判这个开关，非 SQLite 时一律回退到 LIKE。
     */
    public static boolean isSqlite(DbClientHolder holder) {
        return !isMySql(holder) && !isPg(holder);
    }

    /** 是否整数类列（决定主键列是否套自增语法） */
    public static boolean isIntegerType(String type) {
        if (type == null) {
            return false;
        }
        String u = type.trim().toUpperCase();
        return u.equals("INTEGER") || u.equals("INT") || u.equals("BIGINT");
    }

    /**
     * SQLite 列类型 → 当前方言的列类型。
     * 重点：MySQL 的 TEXT 上限只有 64KB，而单条日志实测可达 7MB+，必须抬到 LONGTEXT。
     */
    public static String mapType(DbClientHolder holder, String src) {
        if (src == null || src.trim().length() == 0) {
            return "TEXT";
        }
        String s = src.trim();
        String u = s.toUpperCase();
        if (isPg(holder)) {
            if (u.startsWith("DATETIME") || u.startsWith("TIMESTAMP")) {
                return "TIMESTAMP";
            }
            if (u.startsWith("TINYINT")) {
                return "SMALLINT";
            }
            if (u.equals("INTEGER") || u.equals("INT") || u.equals("BIGINT")) {
                return "BIGINT";
            }
        } else if (isMySql(holder)) {
            if (u.startsWith("DATETIME") || u.startsWith("TIMESTAMP")) {
                return "DATETIME";
            }
            if (u.equals("TEXT")) {
                return "LONGTEXT";
            }
            if (u.equals("INTEGER") || u.equals("INT") || u.equals("BIGINT")) {
                return "BIGINT";
            }
        }
        return s;
    }

    /** 单列定义：主键 + 整数 → 方言自增语法；文本主键 → 类型 + PRIMARY KEY（不加自增） */
    public static String colDef(DbClientHolder holder, String name, String sqliteType, String flag) {
        boolean pk = "1".equals(flag) || "2".equals(flag);
        boolean auto = "1".equals(flag);
        if (pk && auto && isIntegerType(sqliteType)) {
            if (isPg(holder)) {
                return name + " BIGSERIAL PRIMARY KEY";
            }
            if (isMySql(holder)) {
                return name + " BIGINT PRIMARY KEY AUTO_INCREMENT";
            }
            return name + " INTEGER PRIMARY KEY AUTOINCREMENT";
        }
        return name + " " + mapType(holder, sqliteType) + (pk ? " PRIMARY KEY" : "");
    }

    /**
     * 按列定义建表（幂等）。列定义：{ 列名, SQLite 类型, 标记 }，
     * 标记 "1" = 自增主键，"2" = 主键但不自增（显式赋值），"" = 普通列。
     */
    public static void createTable(DbClientHolder holder, String table, String[][] cols) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(colDef(holder, cols[i][0], cols[i][1], cols[i].length > 2 ? cols[i][2] : ""));
        }
        sb.append(")");
        holder.executeUpdate(sb.toString());
    }

    /**
     * 给已有表追加列（兼容老库）。SQLite 走 PRAGMA 探列 + ALTER；
     * 非 SQLite 方言 PRAGMA 会抛异常，则直接尝试 ALTER 并忽略「列已存在」类错误。
     */
    private static void addColumnIfMissing(DbClientHolder holder, String table, String column, String def) {
        try {
            boolean has = false;
            List<Dict> info = holder.executeQuery("PRAGMA table_info(" + table + ")");
            for (Dict d : info) {
                Object n = d.get("name");
                if (n != null && column.equalsIgnoreCase(String.valueOf(n))) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                holder.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + def);
            }
        } catch (Exception e) {
            // 非 SQLite 方言：尝试通用 ALTER，重复列错误静默忽略
            try {
                holder.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + def);
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    /** 由时间戳推导月表名 xt_log_entry_YYYYMM */
    public static String monthTable(long ts) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMM");
        return "xt_log_entry_" + sdf.format(new java.util.Date(ts));
    }

    /** 月表结构（幂等建表） */
    public static void ensureLogTable(DbClientHolder holder, String tableName) {
        createTable(holder, tableName, new String[][] {
                { "id", "INTEGER", "1" },
                { "app_id", "INTEGER", "" },
                { "category_id", "INTEGER", "" },
                { "level", "VARCHAR(10)", "" },
                { "logger", "VARCHAR(200)", "" },
                { "thread", "VARCHAR(100)", "" },
                { "message", "TEXT", "" },
                { "trace_id", "VARCHAR(64)", "" },
                { "src_ip", "VARCHAR(45)", "" },
                { "log_time", "DATETIME", "" },
                { "receive_time", "DATETIME", "" },
                // 超限日志的分片元信息：同一条原始日志的所有片段共享 cont_group，
                // cont_seq 从 0 递增。未拆分的日志两者分别为 NULL / 0。
                { "cont_group", "VARCHAR(32)", "" },
                { "cont_seq", "INTEGER", "" } });
        // 迁移：早期版本的月表没有 src_ip 列（CREATE TABLE IF NOT EXISTS 对已存在的表不会补列），
        // 这里兜底补上，否则文件/命令采集写入的本机 IP 会因「列不存在」直接落库失败。
        addColumnIfMissing(holder, tableName, "src_ip", mapType(holder, "VARCHAR(45)"));
        // 同上：老库补上分片列
        addColumnIfMissing(holder, tableName, "cont_group", mapType(holder, "VARCHAR(32)"));
        addColumnIfMissing(holder, tableName, "cont_seq", mapType(holder, "INTEGER"));
        // 索引：日志表默认没有任何索引，海量数据下「按时间排序 / 计数 / 按级别聚合」都会
        // 退化成全表扫描（连同几十 KB 的 message 文本一起读盘），在 GB 级库上直接超时。
        // 建好索引后这些查询都走小索引（覆盖索引 / 范围扫描），毫秒级完成。
        //   idx_<t>_time     —— 列表「ORDER BY log_time DESC」与首页范围计数（覆盖索引，不碰 message）
        //   idx_<t>_apptime  —— 指定 app 过滤后的列表与计数
        //   idx_<t>_leveltime—— 按级别聚合 + 时间范围
        //   idx_<t>_ip       —— 按来源 IP 过滤（src_ip 无索引时需全表扫描 GB 级 message，实测 5s+）
        createIndexIfMissing(holder, tableName, "idx_" + tableName + "_time", "log_time DESC, id DESC");
        createIndexIfMissing(holder, tableName, "idx_" + tableName + "_apptime", "app_id, log_time DESC, id DESC");
        createIndexIfMissing(holder, tableName, "idx_" + tableName + "_leveltime", "level, log_time DESC");
        createIndexIfMissing(holder, tableName, "idx_" + tableName + "_ip", "src_ip, log_time DESC, id DESC");
        // 分片串联：点开一条被拆开的日志时按 cont_group 捞同一条的所有片段。
        // 未拆分的日志 cont_group 为 NULL，不会进索引，所以这个索引基本只装真正被拆的行，体积可以忽略。
        createIndexIfMissing(holder, tableName, "idx_" + tableName + "_grp", "cont_group, cont_seq");
    }

    /**
     * 建索引（幂等）。
     * MySQL 不支持 CREATE INDEX IF NOT EXISTS（会直接语法报错），故先按标准写法试，
     * 失败则退回不带 IF NOT EXISTS 的写法，再失败（多半是「索引已存在」）就忽略——
     * 索引只是性能优化，不应让建表/迁移这类主流程失败。
     */
    public static void createIndexIfMissing(DbClientHolder holder, String table, String indexName, String columns) {
        try {
            holder.executeUpdate("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + " (" + columns + ")");
            return;
        } catch (Exception e) {
            // MySQL 等方言：换不带 IF NOT EXISTS 的写法重试
        }
        try {
            holder.executeUpdate("CREATE INDEX " + indexName + " ON " + table + " (" + columns + ")");
        } catch (Exception ignore) {
            // 已存在或该方言不支持：忽略，不阻断主流程
        }
    }

    // ==================== 运行期配置 + 日志保留策略 ====================

    /** 配置项表名 */
    public static final String CONFIG_TABLE = "xt_config";

    /** 日志保留月数；0 或空 = 不限制 */
    public static final String KEY_RETENTION_MONTHS = "retention.months";

    /** 单条日志的最大字符数；超过则拆成多条存储。0 或空 = 不限制 */
    public static final String KEY_MAX_CHARS = "ingest.maxChars";

    /** 单条日志默认上限：64KB。见 LogSplitter 的说明 */
    public static final int DEFAULT_MAX_CHARS = 65536;

    /** 配置表结构（幂等）。故意只留 k/v 两列，避免各库 DATETIME 类型差异带来的写入问题 */
    public static void ensureConfigTable(DbClientHolder holder) {
        holder.executeUpdate("CREATE TABLE IF NOT EXISTS " + CONFIG_TABLE
                + " (k VARCHAR(64) PRIMARY KEY, v VARCHAR(200))");
    }

    // ==================== 迁移任务记录 + 数据源切换记录 ====================

    /** 数据源记录表（历史 + 当前，可一键切回） */
    public static final String DS_TABLE = "xt_datasource";

    /** 迁移任务记录表 */
    public static final String JOB_TABLE = "xt_migrate_job";

    /**
     * 两张元数据表（幂等）。
     * 主键都显式给值或自增：xt_migrate_job.id 用「启动时间戳」当业务主键（便于前后端对齐），
     * 所以声明成 BIGINT 且不带自增。
     */
    public static void ensureMetaTables(DbClientHolder holder) {
        createTable(holder, DS_TABLE, new String[][] {
                { "id", "INTEGER", "1" },
                { "label", "VARCHAR(100)", "" },
                { "db_type", "VARCHAR(20)", "" },
                { "url", "VARCHAR(500)", "" },
                { "username", "VARCHAR(100)", "" },
                { "password", "VARCHAR(200)", "" },
                { "is_current", "INTEGER", "" },
                { "create_time", "DATETIME", "" },
                { "last_switch_time", "DATETIME", "" } });
        // id 用「启动时间戳」当业务主键（显式赋值），所以标记成不自增
        createTable(holder, JOB_TABLE, new String[][] {
                { "id", "BIGINT", "2" },
                { "db_type", "VARCHAR(20)", "" },
                { "target", "VARCHAR(500)", "" },
                { "stage", "VARCHAR(50)", "" },
                { "ok", "INTEGER", "" },
                { "running", "INTEGER", "" },
                { "copied", "INTEGER", "" },
                { "error", "TEXT", "" },
                { "start_time", "DATETIME", "" },
                { "end_time", "DATETIME", "" } });
    }

    /**
     * 时间字段的写入值：SQLite 沿用项目既有习惯存 epoch 毫秒（Long）；
     * MySQL / PostgreSQL 的 DATETIME / TIMESTAMP 不接受裸数字，必须给 Timestamp，
     * 否则会报 "Incorrect datetime value: '1791958515865'"。
     */
    public static Object tsValue(DbClientHolder holder, long ms) {
        if (isMySql(holder) || isPg(holder)) {
            return new java.sql.Timestamp(ms);
        }
        return Long.valueOf(ms);
    }

    // ==================== 告警规则与事件 ====================

    /** 告警规则表名 */
    public static final String ALERT_RULE_TABLE = "xt_alert_rule";

    /** 告警事件表名 */
    public static final String ALERT_EVENT_TABLE = "xt_alert_event";

    /**
     * 告警规则与事件两张表（幂等）。
     *
     * <p>规则表保留窗口、阈值、静默、通知渠道（JSON）等评估参数；事件表冗余存规则名与应用名，
     * 避免规则或应用被删后历史事件丢失上下文。status 取值：1 触发中 / 2 已恢复 / 3 已认领。</p>
     */
    public static void ensureAlertTables(DbClientHolder holder) {
        createTable(holder, ALERT_RULE_TABLE, new String[][] {
                { "id", "INTEGER", "1" },
                { "name", "VARCHAR(100)", "" },
                // app_id = 0 或 NULL 表示作用于全部应用
                { "app_id", "BIGINT", "" },
                // 规则类型：LEVEL / KEYWORD / REGEX / RATE
                { "rule_type", "VARCHAR(20)", "" },
                // LEVEL: ERROR[,WARN] 多个用逗号；KEYWORD: 文本；REGEX: Java 正则；RATE: 阈值次/窗口
                { "rule_expr", "VARCHAR(500)", "" },
                // 评估窗口（秒），默认 300
                { "window_sec", "INTEGER", "" },
                // 窗口内达到多少次触发，默认 1
                { "threshold", "INTEGER", "" },
                // 触发后静默时长（秒），默认 600
                { "silence_sec", "INTEGER", "" },
                // JSON：{ "webhook": "https://..." }，后续扩展钉钉 / 飞书 / 邮件
                { "channels", "TEXT", "" },
                { "enabled", "INTEGER", "" },
                { "remark", "VARCHAR(500)", "" },
                { "create_time", "DATETIME", "" },
                { "update_time", "DATETIME", "" } });

        createTable(holder, ALERT_EVENT_TABLE, new String[][] {
                { "id", "INTEGER", "1" },
                { "rule_id", "BIGINT", "" },
                // 冗余存规则名：规则被删后事件流仍可读
                { "rule_name", "VARCHAR(100)", "" },
                { "app_id", "BIGINT", "" },
                { "app_name", "VARCHAR(100)", "" },
                // 触发时窗口内的最高级别（ERROR/WARN/INFO…）
                { "level", "VARCHAR(10)", "" },
                // 摘要：「命中 N 条 / 样例 message 截断 200 字」
                { "summary", "VARCHAR(500)", "" },
                { "sample_message", "TEXT", "" },
                { "hit_count", "INTEGER", "" },
                // 1 触发中 / 2 已恢复 / 3 已认领
                { "status", "INTEGER", "" },
                { "first_time", "DATETIME", "" },
                { "last_time", "DATETIME", "" },
                { "ack_time", "DATETIME", "" },
                { "ack_user", "VARCHAR(100)", "" } });
        // 事件列表按状态过滤 + 时间倒序，是高频查询路径，必须走索引
        createIndexIfMissing(holder, ALERT_EVENT_TABLE, "idx_alert_event_status", "status, last_time DESC");
        createIndexIfMissing(holder, ALERT_EVENT_TABLE, "idx_alert_event_rule", "rule_id, last_time DESC");
    }

    /**
     * 读取时间字段，统一成 epoch 毫秒。
     * 不同驱动给出来的东西不一样：SQLite 给 Long，MySQL 8 驱动给 java.time.LocalDateTime，
     * PG 给 Timestamp，xtboot 也可能把它转成字符串——这里全部兜住。
     */
    public static long tsOf(Object v, long def) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof java.util.Date) {
            return ((java.util.Date) v).getTime();
        }
        if (v instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) v)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (v instanceof java.time.LocalDate) {
            return ((java.time.LocalDate) v).atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        }
        if (v instanceof java.time.Instant) {
            return ((java.time.Instant) v).toEpochMilli();
        }
        if (v instanceof java.time.OffsetDateTime) {
            return ((java.time.OffsetDateTime) v).toInstant().toEpochMilli();
        }
        if (v instanceof java.time.ZonedDateTime) {
            return ((java.time.ZonedDateTime) v).toInstant().toEpochMilli();
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            try {
                return Long.parseLong(s);
            } catch (Exception ignore) {
                // 日期串，走下面的格式解析
            }
            String[] fmts = {
                    "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss"
            };
            for (String f : fmts) {
                try {
                    return new java.text.SimpleDateFormat(f).parse(s).getTime();
                } catch (Exception ignore) {
                }
            }
        }
        return def;
    }

    /**
     * 保证「当前数据源」在记录表里有一条（首次启动时登记，之后幂等）。
     * 这样界面上的数据源列表至少能看到现在连的是哪个库，切走之后也能一键切回来。
     */
    public static void ensureCurrentRecord(DbClientHolder holder, String url, String user, String pwd) {
        try {
            List<Dict> rows = holder.executeQuery("SELECT id FROM " + DS_TABLE + " WHERE is_current = 1");
            if (rows != null && !rows.isEmpty()) {
                return;
            }
            upsertDatasource(holder, null, dbTypeOf(url), url,
                    user == null ? "" : user, pwd == null ? "" : pwd, System.currentTimeMillis(), 1);
        } catch (Exception e) {
            System.err.println("[xtlog] ensure current datasource record failed: " + e.getMessage());
        }
    }

    /**
     * 按 URL 归一化去重写入数据源记录（已存在则只更新「当前标记 / 凭据 / 切换时间」，
     * 不覆盖用户改过的名字）。切换数据源、迁移完成登记目标库都走这里。
     */
    public static void upsertDatasource(DbClientHolder holder, String label, String dbType, String url,
                                        String user, String pwd, long createTime, int isCurrent) {
        try {
            Long id = null;
            List<Dict> all = holder.executeQuery("SELECT id, url FROM " + DS_TABLE);
            if (all != null) {
                for (Dict r : all) {
                    Object u = r.get("url");
                    if (u != null && normUrl(String.valueOf(u)).equals(normUrl(url))) {
                        id = Long.valueOf(((Number) r.get("id")).longValue());
                        break;
                    }
                }
            }
            long now = System.currentTimeMillis();
            if (id != null) {
                if (isCurrent == 1) {
                    holder.executeUpdate("UPDATE " + DS_TABLE
                                    + " SET is_current = 1, last_switch_time = ?, username = ?, password = ? WHERE id = ?",
                            tsValue(holder, now), user, pwd, id);
                } else if (label != null && label.length() > 0) {
                    holder.executeUpdate("UPDATE " + DS_TABLE + " SET label = ? WHERE id = ?", label, id);
                }
                return;
            }
            String lb = label != null && label.length() > 0 ? label : defaultDsLabel(dbType, url);
            holder.executeUpdate("INSERT INTO " + DS_TABLE
                            + " (label, db_type, url, username, password, is_current, create_time, last_switch_time)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    lb, dbType, url, user, pwd, Integer.valueOf(isCurrent),
                    tsValue(holder, createTime), tsValue(holder, now));
        } catch (Exception e) {
            System.err.println("[xtlog] record datasource failed: " + e.getMessage());
        }
    }

    /** 从 JDBC URL 反推数据库类型（sqlite / mysql / postgresql） */
    public static String dbTypeOf(String url) {
        String u = url == null ? "" : url.toLowerCase();
        if (u.startsWith("jdbc:mysql:")) {
            return "mysql";
        }
        if (u.startsWith("jdbc:postgresql:")) {
            return "postgresql";
        }
        if (u.startsWith("jdbc:sqlite:")) {
            return "sqlite";
        }
        return "";
    }

    /** 记录的默认名字：sqlite 显示文件名，其它显示 host:port/db */
    private static String defaultDsLabel(String dbType, String url) {
        String u = url == null ? "" : url;
        if (u.toLowerCase().startsWith("jdbc:sqlite:")) {
            String p = u.substring("jdbc:sqlite:".length());
            int q = p.indexOf('?');
            if (q >= 0) {
                p = p.substring(0, q);
            }
            int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
            String file = slash >= 0 ? p.substring(slash + 1) : p;
            return "SQLite · " + (file.length() == 0 ? p : file);
        }
        String s = u;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int at = s.indexOf("//");
        String tail = at >= 0 ? s.substring(at + 2) : s;
        String type = dbType == null || dbType.length() == 0 ? dbTypeOf(u) : dbType;
        return (type.length() == 0 ? "DB" : type.toUpperCase()) + " · " + tail;
    }

    /** URL 归一：去掉查询参数后比较，避免 WAL / 时区等参数差异造成重复登记 */
    private static String normUrl(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().toLowerCase();
        int q = t.indexOf('?');
        if (q >= 0) {
            t = t.substring(0, q);
        }
        return t;
    }

    public static String getConfig(DbClientHolder holder, String key) {
        try {
            List<Dict> rows = holder.executeQuery("SELECT v FROM " + CONFIG_TABLE + " WHERE k = ?", key);
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            Object v = rows.get(0).get("v");
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            // 配置表还没建（老库直连等极端情况）：当作「未配置」处理，不阻断主流程
            return null;
        }
    }

    public static void setConfig(DbClientHolder holder, String key, String value) {
        ensureConfigTable(holder);
        List<Dict> rows = holder.executeQuery("SELECT v FROM " + CONFIG_TABLE + " WHERE k = ?", key);
        if (rows == null || rows.isEmpty()) {
            holder.executeUpdate("INSERT INTO " + CONFIG_TABLE + " (k, v) VALUES (?, ?)", key, value);
        } else {
            holder.executeUpdate("UPDATE " + CONFIG_TABLE + " SET v = ? WHERE k = ?", value, key);
        }
    }

    /** 读取「日志保留月数」，0 表示不限制 */
    public static int readRetentionMonths(DbClientHolder holder) {
        String v = getConfig(holder, KEY_RETENTION_MONTHS);
        if (v == null || v.trim().length() == 0) {
            return 0;
        }
        try {
            int n = Integer.parseInt(v.trim());
            return n < 0 ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 读取「单条日志最大字符数」。读取失败、为空或 <=0（含显式写 0）一律回退默认 64KB。
     * 不允许返回 0/负数：LogSplitter 把 <=0 视为「不限制拆分」，会让超大单行（如 7MB 堆栈）
     * 原样入库，拖垮写入批次、索引与渲染，并可能让内存被单条撑爆。因此这里强制兜底，
     * 只有真正的正整数（如调大到 200000）才会生效。
     */
    public static int readMaxChars(DbClientHolder holder) {
        String v = getConfig(holder, KEY_MAX_CHARS);
        if (v == null || v.trim().length() == 0) {
            return DEFAULT_MAX_CHARS;
        }
        try {
            int n = Integer.parseInt(v.trim());
            return n <= 0 ? DEFAULT_MAX_CHARS : n;
        } catch (Exception e) {
            return DEFAULT_MAX_CHARS;
        }
    }

    /**
     * 按保留月数清理过期月表，返回被删除的表名。
     * 保留最近 N 个月：以「当月」为第 1 个月往前推，等于或早于第 N 个月之前的整表 DROP。
     * 例：N=6、当月 202610 → 保留 202605~202610，202604 及更早全部删除。
     * N<=0 表示不限制，直接跳过（不会误删任何表）。
     */
    public static List<String> applyRetention(DbClientHolder holder) {
        List<String> dropped = new ArrayList<String>();
        int months = readRetentionMonths(holder);
        if (months <= 0) {
            return dropped;
        }
        Calendar c = Calendar.getInstance();
        int current = c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH);
        // 保留窗口的起点：当月往前 (months-1) 个月
        int cutoff = current - (months - 1);
        for (String t : listLogTables(holder)) {
            int val = monthValueOf(t);
            if (val <= 0 || val >= cutoff) {
                continue;
            }
            try {
                holder.executeUpdate("DROP TABLE " + t);
                dropped.add(t);
                // 同步删掉该月表的全文索引，否则 FTS 虚拟表会变成孤儿，白占几个 GB 还查不到
                FtsService.dropFor(holder, t);
                System.out.println("[xtlog] retention: dropped expired log table " + t);
            } catch (Exception e) {
                System.err.println("[xtlog] retention: drop " + t + " failed: " + e.getMessage());
            }
        }
        return dropped;
    }

    /** 把月表名 xt_log_entry_YYYYMM 折算成可比大小的整数；解析失败返回 0（跳过，绝不误删） */
    private static int monthValueOf(String table) {
        if (table == null || table.length() < 6) {
            return 0;
        }
        String s = table.substring(table.length() - 6);
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            int m = Integer.parseInt(s.substring(4, 6));
            if (m < 1 || m > 12) {
                return 0;
            }
            return y * 12 + (m - 1);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 为「已存在」的月表补齐上述三个索引。
     * 升级老库（如已产生 GB 级数据的库）时，initSchema 会调用本方法一次性补建；
     * 首次创建的新表由 ensureLogTable 在建表时直接带索引。IF NOT EXISTS 保证幂等、可重复执行。
     * 注意：在超大表上建索引是一次性的重操作（需通读全表抽取索引列），
     * 3.8GB 左右的库实测约数秒~数十秒，启动时会卡这一下，之后查询即飞快。
     */
    public static void ensureLogIndexes(DbClientHolder holder) {
        List<String> tables = listLogTables(holder);
        for (String t : tables) {
            createIndexIfMissing(holder, t, "idx_" + t + "_time", "log_time DESC, id DESC");
            createIndexIfMissing(holder, t, "idx_" + t + "_apptime", "app_id, log_time DESC, id DESC");
            createIndexIfMissing(holder, t, "idx_" + t + "_leveltime", "level, log_time DESC");
            createIndexIfMissing(holder, t, "idx_" + t + "_ip", "src_ip, log_time DESC, id DESC");
        }
    }

    /**
     * 枚举 [startTs, endTs] 时间区间覆盖到的所有月表名（升序去重）。
     * 用于把用户选的时间范围翻译成需要 UNION 的月表集合。
     */
    public static List<String> monthsBetween(long startTs, long endTs) {
        List<String> list = new ArrayList<String>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMM");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(Math.min(startTs, endTs));
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(Math.max(startTs, endTs));
        while (!c.after(end)) {
            String t = "xt_log_entry_" + sdf.format(c.getTime());
            if (!list.contains(t)) {
                list.add(t);
            }
            c.add(Calendar.MONTH, 1);
        }
        return list;
    }

    /**
     * 列出库中真实存在的月表名。
     * SQLite 走 sqlite_master；其余方言回退 information_schema（MySQL / PostgreSQL 通用）。
     */
    public static List<String> listLogTables(DbClientHolder holder) {
        List<String> names = new ArrayList<String>();
        try {
            List<Dict> rows = holder.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'xt_log_entry_%'");
            for (Dict r : rows) {
                Object n = r.get("name");
                if (n != null) {
                    names.add(String.valueOf(n));
                }
            }
            return names;
        } catch (Exception e) {
            // 非 SQLite 方言，回退 information_schema
        }
        List<Dict> rows = holder.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'xt_log_entry_%'");
        for (Dict r : rows) {
            Object n = r.get("table_name");
            if (n == null) {
                n = r.get("TABLE_NAME");
            }
            if (n != null) {
                names.add(String.valueOf(n));
            }
        }
        return names;
    }
}
