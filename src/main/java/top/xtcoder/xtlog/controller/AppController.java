package top.xtcoder.xtlog.controller;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.web.annotation.DeleteMapping;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.PathVariable;
import top.xtcoder.xtboot.web.annotation.PostMapping;
import top.xtcoder.xtboot.web.annotation.PutMapping;
import top.xtcoder.xtboot.web.annotation.RequestBody;
import top.xtcoder.xtboot.web.annotation.RequestParam;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.ingest.IngestQueue;
import top.xtcoder.xtlog.service.AppService;
import top.xtcoder.xtlog.service.FtsService;
import top.xtcoder.xtlog.util.DbBusyRetry;

/**
 * 应用管理：新建/查询/修改/删除。新建时自动生成接入 token，并刷新 AppService 索引。
 */
@RestController
@SaCheckLogin
public class AppController {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private AppService appService;

    @Autowired
    private IngestQueue queue;

    /**
     * 应用日志统计（日志总数 / 最新日志时间）缓存。
     * 列表接口每次请求都会全量聚合所有月表，成本随数据量线性增长，且翻页/搜索/增删改都会重触发，
     * 在 GB 级月表上会偶发卡顿。这里用短 TTL 把重聚合压到最多每 STATS_TTL_MS 一次；
     * 聚合失败时保留上一份缓存（graceful degradation），避免损坏的月表把整个应用列表拖挂。
     */
    private static final long STATS_TTL_MS = 15000L;
    private volatile Map<Long, long[]> statsCache = null;
    private volatile long statsCacheTs = 0L;

    /**
     * 应用列表（分页 + 关键词 + 分类筛选）。
     *
     * <p>返回 {@code {list,total,page,pageSize}}：前端表格要分页，就不能继续裸返回数组。
     * 关键词同时匹配名称与备注，LIKE 的转义交给参数绑定，不做字符串拼接。</p>
     */
    @GetMapping("/api/apps")
    public Object list(@RequestParam(value = "keyword", required = false) String keyword,
                       @RequestParam(value = "categoryId", required = false) String categoryId,
                       @RequestParam(value = "page", defaultValue = "1") int page,
                       @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        if (page <= 0) {
            page = 1;
        }
        if (pageSize <= 0) {
            pageSize = 10;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<Object>();
        if (keyword != null && keyword.trim().length() > 0) {
            where.append(" AND (name LIKE ? OR remark LIKE ?)");
            params.add("%" + keyword.trim() + "%");
            params.add("%" + keyword.trim() + "%");
        }
        if (categoryId != null && categoryId.trim().length() > 0 && !"0".equals(categoryId.trim())) {
            where.append(" AND category_id = ?");
            params.add(Long.valueOf(Long.parseLong(categoryId.trim())));
        }

        long total = 0L;
        List<Dict> cnt = holder.executeQuery("SELECT COUNT(*) AS c FROM app" + where, params.toArray());
        if (!cnt.isEmpty() && cnt.get(0).get("c") != null) {
            total = ((Number) cnt.get(0).get("c")).longValue();
        }

        String sql = "SELECT id, category_id, name, token, source_type, file_paths, cmd, enabled, remark, create_time, update_time"
                + " FROM app" + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        int limit = pageSize;
        int offset = (page - 1) * pageSize;
        List<Object> pageParams = new ArrayList<Object>(params);
        pageParams.add(Integer.valueOf(limit));
        pageParams.add(Integer.valueOf(offset));
        List<Dict> rows = holder.executeQuery(sql, pageParams.toArray());
        // 给每个应用附上「日志总数」与「最新一条日志时间」——单次跨月表聚合，避免逐 app 查询拖慢列表。
        attachLogStats(rows);
        // enabled 归一为布尔：DB 里是 INTEGER(0/1)，NULL 视为启用；
        // 前端按布尔判断，这里统一转换，避免 0/false 类型不一致导致状态显示/切换错乱。
        for (Dict r : rows) {
            r.put("enabled", Boolean.valueOf(parseEnabled(r.get("enabled"))));
        }

        Dict resp = new Dict();
        resp.put("list", rows);
        resp.put("total", Long.valueOf(total));
        resp.put("page", Integer.valueOf(page));
        resp.put("pageSize", Integer.valueOf(pageSize));
        return ApiResponse.ok(resp);
    }

    /** 下拉用的精简列表：只带 id / name，给筛选框与表单使用 */
    @GetMapping("/api/apps/options")
    public Object options() {
        List<Dict> rows = holder.executeQuery("SELECT id, name FROM app ORDER BY id DESC");
        return ApiResponse.ok(rows);
    }

    @PostMapping("/api/apps")
    public Object create(@RequestBody Dict body) {
        String name = (String) body.get("name");
        if (name == null || name.length() == 0) {
            Dict d = new Dict();
            d.put("error", "name required");
            return ApiResponse.ok(d);
        }
        Object cid = body.get("categoryId");
        long categoryId = cid instanceof Number ? ((Number) cid).longValue() : 0L;
        String sourceType = (String) body.get("sourceType");
        if (sourceType == null) {
            sourceType = "HTTP";
        }
        String filePaths = (String) body.get("filePaths");
        String cmd = (String) body.get("cmd");
        String remark = (String) body.get("remark");
        String token = UUID.randomUUID().toString().replace("-", "");

        Dict row = new Dict();
        row.put("category_id", categoryId);
        row.put("name", name);
        row.put("token", token);
        row.put("source_type", sourceType);
        row.put("file_paths", filePaths);
        row.put("cmd", cmd);
        row.put("remark", remark);
        row.put("enabled", Integer.valueOf(1));
        row.put("create_time", new Date());
        row.put("update_time", new Date());

        // SQLite 单写者：INSERT 可能短暂撞上后台落库 / FTS 回填持有的写锁而报 SQLITE_BUSY。
        // 这是瞬时竞争，重试即可，不应把错误甩给用户（否则表现为「添加应用报错」）。
        try {
            DbBusyRetry.retry(() -> holder.insert("app", row), 8, 200L);
        } catch (Exception e) {
            Dict d = new Dict();
            d.put("error", "create app failed: " + e.getMessage());
            return ApiResponse.ok(d);
        }

        final long[] newId = { 0L };
        try {
            // 取回自增 id 同样可能在竞争窗口里遇锁，重试；取不到也不影响「已创建」的结果
            DbBusyRetry.retry(() -> {
                List<Dict> idRows = holder.executeQuery("SELECT MAX(id) AS id FROM app");
                if (!idRows.isEmpty() && idRows.get(0).get("id") != null) {
                    newId[0] = ((Number) idRows.get(0).get("id")).longValue();
                }
            }, 8, 200L);
        } catch (Exception ignore) {
            // 取 id 失败不影响创建结果，前端大多只用 token
        }
        appService.refresh();

        Dict r = new Dict();
        r.put("id", Long.valueOf(newId[0]));
        r.put("token", token);
        return ApiResponse.ok(r);
    }

    @PutMapping("/api/apps/{id}")
    public Object update(@PathVariable("id") long id, @RequestBody Dict body) {
        StringBuilder sql = new StringBuilder("UPDATE app SET update_time = ?");
        List<Object> params = new ArrayList<Object>();
        params.add(new Date());
        if (body.containsKey("name")) {
            sql.append(", name = ?");
            params.add(body.get("name"));
        }
        if (body.containsKey("categoryId")) {
            sql.append(", category_id = ?");
            params.add(((Number) body.get("categoryId")).longValue());
        }
        if (body.containsKey("sourceType")) {
            sql.append(", source_type = ?");
            params.add(body.get("sourceType"));
        }
        if (body.containsKey("filePaths")) {
            sql.append(", file_paths = ?");
            params.add(body.get("filePaths"));
        }
        if (body.containsKey("cmd")) {
            sql.append(", cmd = ?");
            params.add(body.get("cmd"));
        }
        if (body.containsKey("remark")) {
            sql.append(", remark = ?");
            params.add(body.get("remark"));
        }
        if (body.containsKey("enabled")) {
            sql.append(", enabled = ?");
            Object eo = body.get("enabled");
            int ev = (eo instanceof Boolean) ? (((Boolean) eo).booleanValue() ? 1 : 0)
                    : (eo instanceof Number) ? ((Number) eo).intValue()
                    : ("0".equals(String.valueOf(eo).trim()) ? 0 : 1);
            params.add(Integer.valueOf(ev));
        }
        sql.append(" WHERE id = ?");
        params.add(id);
        // 同 create：SQLite 单写者，短时撞锁重试即可，不甩错给用户
        try {
            DbBusyRetry.retry(() -> holder.executeUpdate(sql.toString(), params), 8, 200L);
        } catch (Exception e) {
            Dict d = new Dict();
            d.put("error", "update app failed: " + e.getMessage());
            return ApiResponse.ok(d);
        }
        appService.refresh();

        Dict d = new Dict();
        d.put("updated", id);
        return ApiResponse.ok(d);
    }

    @DeleteMapping("/api/apps/{id}")
    public Object delete(@PathVariable("id") long id) {
        // Bug 修复：删除应用前，先把该应用分布在所有月表里的日志清掉，避免留下「孤儿日志」。
        // 关键顺序（修复「删完库行又被落库线程写回复活」的竞态）：
        //   1) 先把队列里已接收、尚未落库的该应用日志全部丢弃（removeByApp），
        //      否则 LogBatchWriter 在 deleteAppLogs 之后才把它们 flush 进库，会复活成孤儿；
        //   2) 再清掉库里现有日志（含 FTS 孤儿索引项清理）；
        //   3) 删应用行；
        //   4) 重建缓存：应用已从库消失，新推送 token 无法解析 → IngestController 自然拒绝，
        //      且 LogBatchWriter 的「应用已删则丢弃」兜底也会拦掉任何漏网的在途日志。
        try {
            queue.removeByApp(id);
        } catch (Exception ignore) {
            // 队列操作失败不影响删除主流程
        }
        // 同时顺手清理这些行在 FTS 外部内容索引里的残留项（孤儿索引项无害：查询路径按
        // id IN 只捞存在的行、COUNT 落在基表，不会报错，只是占磁盘），回收空间。
        try {
            deleteAppLogs(id);
        } catch (Exception e) {
            // 日志清理失败不应阻断应用本身的删除；记录后继续
            System.err.println("[xtlog] delete app logs failed (app=" + id + "): " + e.getMessage());
        }
        // 同 create：SQLite 单写者，短时撞锁重试即可，不甩错给用户
        try {
            DbBusyRetry.retry(() -> holder.executeUpdate("DELETE FROM app WHERE id = ?", id), 8, 200L);
        } catch (Exception e) {
            Dict d = new Dict();
            d.put("error", "delete app failed: " + e.getMessage());
            return ApiResponse.ok(d);
        }
        appService.refresh();
        Dict d = new Dict();
        d.put("deleted", id);
        return ApiResponse.ok(d);
    }

    /**
     * 启用 / 停用应用。
     * <ul>
     *   <li>停用后：HTTP 推送直接丢弃（{@link IngestController} 拒绝）、CMD/FILE 采集器不再启动
     *       （{@code reconcile} 跳过 / 停掉），历史日志保留可查。</li>
     *   <li>启用后：恢复正常摄入与采集。</li>
     * </ul>
     * 采集器每 5s（CMD）/ 2s（FILE）巡检一次，状态变更后最多等一个周期即生效。
     */
    @PutMapping("/api/apps/{id}/enable")
    public Object setEnabled(@PathVariable("id") long id, @RequestBody Dict body) {
        boolean on = true;
        Object e = (body == null) ? null : body.get("enabled");
        if (e instanceof Boolean) {
            on = ((Boolean) e).booleanValue();
        } else if (e instanceof Number) {
            on = ((Number) e).intValue() != 0;
        } else if (e instanceof String) {
            String s = ((String) e).trim();
            on = !"0".equals(s) && !"false".equalsIgnoreCase(s) && !"off".equalsIgnoreCase(s);
        }
        final int enabledVal = on ? 1 : 0;
        try {
            DbBusyRetry.retry(() -> holder.executeUpdate(
                    "UPDATE app SET enabled = ?, update_time = ? WHERE id = ?",
                    Integer.valueOf(enabledVal), new Date(), Long.valueOf(id)), 8, 200L);
        } catch (Exception ex) {
            Dict d = new Dict();
            d.put("error", "set enabled failed: " + ex.getMessage());
            return ApiResponse.ok(d);
        }
        appService.refresh();
        Dict d = new Dict();
        d.put("id", Long.valueOf(id));
        d.put("enabled", Boolean.valueOf(enabledVal != 0));
        return ApiResponse.ok(d);
    }

    /**
     * 删除应用前，清掉它分布在所有月表里的日志。
     * 分批处理：每批先清对应的 FTS 孤儿索引项（外部内容表 'delete' 命令，不需要基表行存在），
     * 再删这批基表行；避免一次性把整张 GB 级月表读进内存。
     */
    private void deleteAppLogs(long appId) {
        List<String> tables = DataSourceConfig.listLogTables(holder);
        for (String t : tables) {
            long last = 0L;
            while (true) {
                List<Dict> batch;
                try {
                    batch = holder.executeQuery(
                            "SELECT id FROM " + t + " WHERE app_id = ? AND id > ? ORDER BY id LIMIT 2000",
                            Long.valueOf(appId), Long.valueOf(last));
                } catch (Exception e) {
                    System.err.println("[xtlog] delete app logs scan failed on " + t + ": " + e.getMessage());
                    break;
                }
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                long max = last;
                String fts = FtsService.ftsTableOf(t);
                boolean haveFts = (fts != null) && ftsTableExists(fts);
                for (Dict d : batch) {
                    Object idv = d.get("id");
                    if (!(idv instanceof Number)) {
                        continue;
                    }
                    long id = ((Number) idv).longValue();
                    max = id;
                    // 清理 FTS 孤儿（最佳努力：未进索引 / 已删的行会忽略异常）
                    if (haveFts) {
                        try {
                            holder.executeUpdate(
                                    "INSERT INTO " + fts + "(" + fts + ", rowid, rank) VALUES('delete', ?, NULL)",
                                    Long.valueOf(id));
                        } catch (Exception ignore) {
                            // 行未进索引或已不存在，忽略
                        }
                    }
                }
                try {
                    holder.executeUpdate(
                            "DELETE FROM " + t + " WHERE app_id = ? AND id > ? AND id <= ?",
                            Long.valueOf(appId), Long.valueOf(last), Long.valueOf(max));
                } catch (Exception e) {
                    System.err.println("[xtlog] delete app logs failed on " + t + ": " + e.getMessage());
                    break;
                }
                if (max <= last) {
                    break;
                }
                last = max;
            }
        }
    }

    /** FTS 虚拟表是否存在（避免对没建索引的月表执行 'delete' 命令报错） */
    private boolean ftsTableExists(String fts) {
        try {
            List<Dict> r = holder.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name = ?", fts);
            return r != null && !r.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 把 app 的 enabled 字段统一解析为布尔：NULL/不存在视为启用，0 视为停用，其余视为启用 */
    private static boolean parseEnabled(Object en) {
        if (en == null) {
            return true;
        }
        if (en instanceof Number) {
            return ((Number) en).intValue() != 0;
        }
        return !"0".equals(String.valueOf(en).trim());
    }

    /**
     * 给应用列表的每一行附上「日志总数」和「最新一条日志时间」。
     *
     * <p>做法：枚举全部月表，每张表各跑一次 {@code GROUP BY app_id} 同时拿到
     * COUNT 与 MAX(log_time)，结果在 Java 侧按 app 累加——每个应用只需一轮查询，
     * 不做 N 次单应用查询。
     * 列表本身只查 app 表（数据量小），与日志库解耦。</p>
     *
     * <p>该聚合在 GB 级月表上较重，且会被列表的翻页/搜索/增删改反复触发，
     * 故 {@link #getLogStats()} 对其做了 15s TTL 缓存与失败兜底（见类字段说明）。</p>
     */
    private void attachLogStats(List<Dict> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<Long, long[]> stats = getLogStats();
        for (Dict r : rows) {
            Object idObj = r.get("id");
            long id = idObj == null ? 0L : ((Number) idObj).longValue();
            long[] s = stats.get(Long.valueOf(id));
            r.put("log_count", Long.valueOf(s == null ? 0L : s[0]));
            r.put("last_log_time", s == null || s[1] == 0L ? null : Long.valueOf(s[1]));
        }
    }

    /**
     * 取「应用 -> {日志总数, 最新日志时间}」统计，带 15s TTL 缓存。
     * 缓存未命中才重算；重算整体失败时（如 listLogTables 异常）返回上一份缓存，
     * 保证应用列表在日志库异常时仍能打开（统计数字可能略旧，但列表不挂）。
     */
    private Map<Long, long[]> getLogStats() {
        long now = System.currentTimeMillis();
        Map<Long, long[]> cache = statsCache;
        if (cache != null && (now - statsCacheTs) < STATS_TTL_MS) {
            return cache;
        }
        Map<Long, long[]> fresh = computeLogStats();
        if (fresh != null) {
            // 仅在「真的算出了内容」或「没有旧缓存可回退」时才覆盖，避免一次全表失败把计数清零
            if (!fresh.isEmpty() || cache == null) {
                statsCache = fresh;
                statsCacheTs = now;
                return fresh;
            }
            // fresh 为空且存在旧缓存：保留旧缓存（本次聚合可能整体失败）
            return cache;
        }
        // computeLogStats 返回 null = 聚合过程整体异常：回退旧缓存
        if (cache != null) {
            return cache;
        }
        return new HashMap<Long, long[]>();
    }

    /**
     * 真正执行跨月表聚合。单张月表查询失败（如该表损坏）只跳过该表、记日志，不影响其它应用；
     * 仅当 listLogTables 自身异常或整体不可用时返回 null，由调用方决定回退。
     */
    private Map<Long, long[]> computeLogStats() {
        Map<Long, long[]> stats = new HashMap<Long, long[]>();
        try {
            List<String> tables = DataSourceConfig.listLogTables(holder);
            // 按月表分别 GROUP BY 后在 Java 侧累加：
            // 既避免了 UNION ALL + 聚合把全 MONTH 行（含几十 KB 的 message）物化成临时表，
            // 也让每张表可以走自己的覆盖索引（app_id, log_time），MySQL/PG 目标同样受益。
            if (!tables.isEmpty()) {
                for (String t : tables) {
                    List<Dict> agg;
                    try {
                        agg = holder.executeQuery(
                                "SELECT app_id, COUNT(*) AS c, MAX(log_time) AS mt FROM " + t + " GROUP BY app_id");
                    } catch (Exception e) {
                        // 单张月表损坏/查询失败：跳过该表，其余应用统计照常
                        System.err.println("[xtlog] app-stats scan failed on " + t + ": " + e.getMessage());
                        continue;
                    }
                    if (agg == null) {
                        continue;
                    }
                    for (Dict d : agg) {
                        Object aid = d.get("app_id");
                        if (aid == null) {
                            continue;
                        }
                        long id = ((Number) aid).longValue();
                        long c = d.get("c") == null ? 0L : ((Number) d.get("c")).longValue();
                        // MAX(log_time) 的类型随方言变：SQLite 是 Long（epoch 毫秒），
                        // MySQL 驱动给的是 java.time.LocalDateTime，PG 可能是 Timestamp。
                        // 统一走 tsOf 归一成毫秒，不能直接强转 Number（MySQL 上会 CCE）。
                        long mt = DataSourceConfig.tsOf(d.get("mt"), 0L);
                        // 同一 app 跨月表累加
                        long[] prev = stats.get(Long.valueOf(id));
                        if (prev == null) {
                            stats.put(Long.valueOf(id), new long[] { c, mt });
                        } else {
                            prev[0] += c;
                            if (mt > prev[1]) {
                                prev[1] = mt;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[xtlog] app-stats compute failed: " + e.getMessage());
            return null; // 触发回退
        }
        return stats;
    }
}
