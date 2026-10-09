package top.xtcoder.xtlog.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.RequestParam;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.web.model.ApiResponse;
import top.xtcoder.xtboot.web.sse.SseEmitter;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.ingest.LogBroadcast;
import top.xtcoder.xtlog.service.AppService;

/**
 * 日志查询与实时订阅。
 *  - GET /api/logs          历史查询（跨月 UNION ALL + 复合游标），返回 hasMore/nextCursor
 *  - GET /api/logs/stream   SSE 实时推送，按 appId（或 token 推导的 appId）分组
 *
 * 月表策略：日志按 xt_log_entry_YYYYMM 分表存储，查询时按 startTime/endTime 推导出
 * 需要扫描的月表集合，再 UNION ALL 成一张逻辑表；游标为 "logTime_id" 复合键，
 * 因为跨月之后 id 会重复，单靠 id 无法唯一定位（这也是不能用 OFFSET 分页的原因）。
 */
@RestController
@SaCheckLogin
public class LogQueryController {

    /** 月表投影字段：count 时只取 id */
    private static final String COLUMNS =
            "id, app_id, category_id, level, logger, thread, message, trace_id, src_ip, log_time, receive_time";

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private LogBroadcast broadcast;

    @Autowired
    private AppService appService;

    @GetMapping("/api/logs/stream")
    public SseEmitter stream(@RequestParam(value = "appId", required = false) String appId,
                             @RequestParam(value = "token", required = false) String token) {
        String key;
        if (appId != null && appId.length() > 0) {
            key = appId;
        } else if (token != null) {
            AppService.AppInfo app = appService.resolveByToken(token);
            key = (app == null) ? "*" : String.valueOf(app.id);
        } else {
            key = "*";
        }
        final String fKey = key;
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onDisconnect(new Runnable() {
            @Override
            public void run() {
                broadcast.remove(fKey, emitter);
            }
        });
        emitter.onCompletion(new Runnable() {
            @Override
            public void run() {
                broadcast.remove(fKey, emitter);
            }
        });
        emitter.startHeartbeat(15000);
        broadcast.subscribe(fKey, emitter);
        return emitter;
    }

    /**
     * 历史查询。两种分页模式二选一：
     *  - 游标模式（默认）：传 cursor，返回 hasMore / nextCursor，适合「加载更多」与控制台首屏
     *  - 页码模式：传 page（&pageSize），返回 total，适合表格分页器
     * 两种模式共用同一套 WHERE 条件与月表集合。
     */
    @GetMapping("/api/logs")
    public Object query(@RequestParam(value = "appId", required = false) String appId,
                        @RequestParam(value = "categoryId", required = false) String categoryId,
                        @RequestParam(value = "level", required = false) String level,
                        @RequestParam(value = "keyword", required = false) String keyword,
                        @RequestParam(value = "startTime", required = false) String startTime,
                        @RequestParam(value = "endTime", required = false) String endTime,
                        @RequestParam(value = "cursor", required = false) String cursor,
                        @RequestParam(value = "limit", defaultValue = "100") int limit,
                        @RequestParam(value = "page", required = false) String page,
                        @RequestParam(value = "pageSize", required = false) String pageSize) {
        if (limit <= 0 || limit > 500) {
            limit = 100;
        }
        int pageNo = parseInt(page, 0);
        int size = parseInt(pageSize, 20);
        if (size <= 0 || size > 200) {
            size = 20;
        }
        boolean pageMode = pageNo > 0;
        long now = System.currentTimeMillis();
        Long st = parseLong(startTime);
        Long et = parseLong(endTime);

        // 1) 时间范围 -> 候选月表；默认覆盖「当前月 + 上个月」
        List<String> candidates;
        if (st != null && et != null) {
            candidates = DataSourceConfig.monthsBetween(st.longValue(), et.longValue());
        } else if (st != null) {
            candidates = DataSourceConfig.monthsBetween(st.longValue(), now);
        } else if (et != null) {
            candidates = DataSourceConfig.monthsBetween(et.longValue() - 86400000L, et.longValue());
        } else {
            candidates = DataSourceConfig.monthsBetween(now - 86400000L * 31, now);
        }

        // 2) 与库中真实存在的月表求交集
        List<String> existing = DataSourceConfig.listLogTables(holder);
        List<String> tables = new ArrayList<String>();
        for (String t : candidates) {
            if (existing.contains(t)) {
                tables.add(t);
            }
        }
        // 全新库：当前月表可能尚未创建，补建一张，避免首次查询报错
        String curTable = DataSourceConfig.monthTable(now);
        if (tables.isEmpty() && candidates.contains(curTable)) {
            DataSourceConfig.ensureLogTable(holder, curTable);
            tables.add(curTable);
        }
        if (tables.isEmpty()) {
            Dict empty = new Dict();
            empty.put("list", new ArrayList<Dict>());
            empty.put("hasMore", Boolean.FALSE);
            empty.put("nextCursor", null);
            empty.put("total", Long.valueOf(0L));
            empty.put("scannedTables", tables);
            return ApiResponse.ok(empty);
        }

        // 3) 条件拼装（页码模式与游标模式共用）
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        if (appId != null && appId.length() > 0) {
            where.append(" AND app_id = ?");
            params.add(Long.valueOf(Long.parseLong(appId)));
        }
        if (categoryId != null && categoryId.length() > 0) {
            where.append(" AND category_id = ?");
            params.add(Long.valueOf(Long.parseLong(categoryId)));
        }
        if (level != null && level.length() > 0) {
            where.append(" AND level = ?");
            params.add(level);
        }
        if (keyword != null && keyword.length() > 0) {
            where.append(" AND message LIKE ?");
            params.add("%" + keyword + "%");
        }
        if (st != null) {
            where.append(" AND log_time >= ?");
            params.add(st);
        }
        if (et != null) {
            where.append(" AND log_time <= ?");
            params.add(et);
        }

        Dict resp = new Dict();
        resp.put("scannedTables", tables);

        if (pageMode) {
            // ---- 页码模式：先 COUNT 再 LIMIT/OFFSET ----
            // 注意：子查询必须投影出 WHERE 用到的全部列（app_id / level / log_time …），
            // 只 SELECT id 会让外层条件报 no such column。
            List<Dict> cnt = holder.executeQuery(
                    "SELECT COUNT(1) AS cnt FROM (" + unionSelect(tables, COLUMNS) + ") t WHERE 1=1" + where,
                    params.toArray());
            long total = 0L;
            if (!cnt.isEmpty()) {
                total = toLong(cnt.get(0).get("cnt"));
            }
            List<Object> pparams = new ArrayList<Object>(params);
            String sql = "SELECT * FROM (" + unionSelect(tables, COLUMNS) + ") t WHERE 1=1" + where
                    + " ORDER BY log_time DESC, id DESC LIMIT ? OFFSET ?";
            pparams.add(Integer.valueOf(size));
            pparams.add(Integer.valueOf((pageNo - 1) * size));
            List<Dict> rows = holder.executeQuery(sql, pparams.toArray());
            resp.put("list", rows);
            resp.put("total", Long.valueOf(total));
            resp.put("page", Integer.valueOf(pageNo));
            resp.put("pageSize", Integer.valueOf(size));
            resp.put("hasMore", Boolean.valueOf(pageNo * size < total));
            return ApiResponse.ok(resp);
        }

        // ---- 游标模式 ----
        StringBuilder sql = new StringBuilder("SELECT * FROM (").append(unionSelect(tables, COLUMNS))
                .append(") t WHERE 1=1").append(where);
        // 复合游标 "logTime_id"：跨月后 id 会重复，必须用 log_time + id 才能唯一定位
        if (cursor != null && cursor.length() > 0) {
            long curTime;
            long curId = 0L;
            int idx = cursor.indexOf('_');
            if (idx > 0) {
                curTime = parseLong0(cursor.substring(0, idx));
                curId = parseLong0(cursor.substring(idx + 1));
            } else {
                curTime = parseLong0(cursor);
            }
            if (curTime > 0) {
                sql.append(" AND (log_time < ? OR (log_time = ? AND id < ?))");
                params.add(Long.valueOf(curTime));
                params.add(Long.valueOf(curTime));
                params.add(Long.valueOf(curId));
            }
        }
        sql.append(" ORDER BY log_time DESC, id DESC LIMIT ?");
        params.add(Integer.valueOf(limit + 1));

        List<Dict> rows = holder.executeQuery(sql.toString(), params.toArray());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<Dict>(rows.subList(0, limit));
        }
        String nextCursor = null;
        if (!rows.isEmpty()) {
            Dict last = rows.get(rows.size() - 1);
            nextCursor = toLong(last.get("log_time")) + "_" + toLong(last.get("id"));
        }
        resp.put("list", rows);
        resp.put("hasMore", Boolean.valueOf(hasMore));
        resp.put("nextCursor", nextCursor);
        return ApiResponse.ok(resp);
    }

    /** 月表 UNION ALL 子句；count 时只取 id 减少一次全字段投影 */
    private static String unionSelect(List<String> tables, String columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                sb.append(" UNION ALL ");
            }
            sb.append("SELECT ").append(columns).append(" FROM ").append(tables.get(i));
        }
        return sb.toString();
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.length() == 0) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static Long parseLong(String s) {
        if (s == null || s.length() == 0) {
            return null;
        }
        try {
            return Long.valueOf(Long.parseLong(s.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private static long parseLong0(String s) {
        Long v = parseLong(s);
        return v == null ? 0L : v.longValue();
    }

    private static long toLong(Object o) {
        if (o == null) {
            return 0L;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }
}
