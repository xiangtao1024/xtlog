package top.xtcoder.xtlog.controller;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
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
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.AppService;
import top.xtcoder.xtlog.service.AppService.AppInfo;
import top.xtcoder.xtlog.util.DbBusyRetry;

/**
 * 告警规则与事件管理。
 *
 * <p>规则（{@code /api/alert/rules}）：</p>
 * <ul>
 *   <li>{@code LEVEL} —— 规则表达式填级别词，如 {@code ERROR} 或 {@code ERROR,WARN}，多级逗号分隔</li>
 *   <li>{@code KEYWORD} —— 表达式填关键词，按 {@code message LIKE '%kw%'} 命中</li>
 *   <li>{@code REGEX} —— Java 正则，先 LIKE 兜底捞候选行，再 Java 侧 Pattern 过滤</li>
 *   <li>{@code RATE} —— 表达式填「级别（如 ERROR）」，窗口内同级别命中数 ≥ threshold 即触发</li>
 * </ul>
 *
 * <p>事件（{@code /api/alert/events}）：1 触发中 / 2 已恢复 / 3 已认领。</p>
 *
 * <p>说明：规则增删改与启停都直接走库，{@link top.xtcoder.xtlog.ingest.AlertEngine} 的下个扫描周期
 * 会读到最新规则。SQLite 单写者短暂撞锁由 {@link DbBusyRetry} 兜住，不甩错给前端。</p>
 */
@RestController
@SaCheckLogin
public class AlertController {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private AppService appService;

    // ==================== 规则 CRUD ====================

    @GetMapping("/api/alert/rules")
    public Object listRules(@RequestParam(value = "keyword", required = false) String keyword,
                            @RequestParam(value = "enabled", required = false) String enabled,
                            @RequestParam(value = "page", defaultValue = "1") int page,
                            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        if (page <= 0) {
            page = 1;
        }
        if (pageSize <= 0) {
            pageSize = 20;
        }
        if (pageSize > 200) {
            pageSize = 200;
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<Object>();
        if (keyword != null && keyword.trim().length() > 0) {
            where.append(" AND (name LIKE ? OR remark LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            params.add(kw);
            params.add(kw);
        }
        if (enabled != null && enabled.trim().length() > 0) {
            where.append(" AND enabled = ?");
            params.add(Integer.valueOf("1".equals(enabled.trim()) ? 1 : 0));
        }

        long total = 0L;
        List<Dict> cnt = holder.executeQuery(
                "SELECT COUNT(*) AS c FROM " + DataSourceConfig.ALERT_RULE_TABLE + where, params.toArray());
        if (!cnt.isEmpty() && cnt.get(0).get("c") != null) {
            total = ((Number) cnt.get(0).get("c")).longValue();
        }

        String sql = "SELECT id, name, app_id, rule_type, rule_expr, window_sec, threshold, silence_sec,"
                + " channels, enabled, remark, create_time, update_time"
                + " FROM " + DataSourceConfig.ALERT_RULE_TABLE + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<Object>(params);
        pageParams.add(Integer.valueOf(pageSize));
        pageParams.add(Integer.valueOf((page - 1) * pageSize));
        List<Dict> rows = holder.executeQuery(sql, pageParams.toArray());
        // 附 app 名，便于规则表格直接展示「作用于哪个应用」
        for (Dict r : rows) {
            Object aid = r.get("app_id");
            if (aid != null && aid instanceof Number && ((Number) aid).longValue() > 0) {
                AppInfo info = appService.resolveById(((Number) aid).longValue());
                r.put("app_name", info == null ? ("应用#" + aid) : info.name);
            } else {
                r.put("app_name", "全部应用");
            }
        }

        Dict resp = new Dict();
        resp.put("list", rows);
        resp.put("total", Long.valueOf(total));
        resp.put("page", Integer.valueOf(page));
        resp.put("pageSize", Integer.valueOf(pageSize));
        return ApiResponse.ok(resp);
    }

    @PostMapping("/api/alert/rules")
    public Object createRule(@RequestBody Dict body) {
        String name = (String) body.get("name");
        if (name == null || name.trim().length() == 0) {
            Dict d = new Dict();
            d.put("error", "name required");
            return ApiResponse.ok(d);
        }
        Dict row = new Dict();
        row.put("name", name.trim());
        row.put("app_id", readLong(body, "appId", 0L));
        row.put("rule_type", strOr(body.get("ruleType"), "LEVEL"));
        row.put("rule_expr", strOr(body.get("ruleExpr"), ""));
        row.put("window_sec", Integer.valueOf(readInt(body, "windowSec", 300)));
        row.put("threshold", Integer.valueOf(readInt(body, "threshold", 1)));
        row.put("silence_sec", Integer.valueOf(readInt(body, "silenceSec", 600)));
        row.put("channels", strOr(body.get("channels"), ""));
        row.put("enabled", Integer.valueOf(body.get("enabled") == null
                || "true".equals(String.valueOf(body.get("enabled")))
                || "1".equals(String.valueOf(body.get("enabled"))) ? 1 : 0));
        row.put("remark", strOr(body.get("remark"), ""));
        row.put("create_time", new Date());
        row.put("update_time", new Date());

        try {
            DbBusyRetry.retry(() -> holder.insert(DataSourceConfig.ALERT_RULE_TABLE, row), 8, 200L);
        } catch (Exception e) {
            Dict d = new Dict();
            d.put("error", "create rule failed: " + e.getMessage());
            return ApiResponse.ok(d);
        }
        Dict r = new Dict();
        r.put("ok", Boolean.TRUE);
        return ApiResponse.ok(r);
    }

    @PutMapping("/api/alert/rules/{id}")
    public Object updateRule(@PathVariable("id") long id, @RequestBody Dict body) {
        StringBuilder sql = new StringBuilder("UPDATE " + DataSourceConfig.ALERT_RULE_TABLE + " SET update_time = ?");
        List<Object> params = new ArrayList<Object>();
        params.add(new Date());
        appendIfHas(body, "name", "name", sql, params);
        appendIfHas(body, "appId", "app_id", sql, params);
        appendIfHas(body, "ruleType", "rule_type", sql, params);
        appendIfHas(body, "ruleExpr", "rule_expr", sql, params);
        appendIfHas(body, "windowSec", "window_sec", sql, params);
        appendIfHas(body, "threshold", "threshold", sql, params);
        appendIfHas(body, "silenceSec", "silence_sec", sql, params);
        appendIfHas(body, "channels", "channels", sql, params);
        if (body.containsKey("enabled")) {
            sql.append(", enabled = ?");
            Object v = body.get("enabled");
            // 兼容前端三种写法：true / "1" / 1
            int en = (v instanceof Boolean) ? (((Boolean) v) ? 1 : 0)
                    : "1".equals(String.valueOf(v)) || "true".equalsIgnoreCase(String.valueOf(v)) ? 1 : 0;
            params.add(Integer.valueOf(en));
        }
        appendIfHas(body, "remark", "remark", sql, params);
        sql.append(" WHERE id = ?");
        params.add(Long.valueOf(id));
        try {
            DbBusyRetry.retry(() -> holder.executeUpdate(sql.toString(), params), 8, 200L);
        } catch (Exception e) {
            Dict d = new Dict();
            d.put("error", "update rule failed: " + e.getMessage());
            return ApiResponse.ok(d);
        }
        Dict r = new Dict();
        r.put("updated", Long.valueOf(id));
        return ApiResponse.ok(r);
    }

    @DeleteMapping("/api/alert/rules/{id}")
    public Object deleteRule(@PathVariable("id") long id) {
        try {
            DbBusyRetry.retry(() -> holder.executeUpdate(
                    "DELETE FROM " + DataSourceConfig.ALERT_RULE_TABLE + " WHERE id = ?", id), 8, 200L);
        } catch (Exception e) {
            Dict d = new Dict();
            d.put("error", "delete rule failed: " + e.getMessage());
            return ApiResponse.ok(d);
        }
        Dict r = new Dict();
        r.put("deleted", Long.valueOf(id));
        return ApiResponse.ok(r);
    }

    /** 启停切换：POST /api/alert/rules/{id}/toggle  body: { enabled: 1/0 } */
    @PostMapping("/api/alert/rules/{id}/toggle")
    public Object toggleRule(@PathVariable("id") long id, @RequestBody Dict body) {
        Object v = body == null ? null : body.get("enabled");
        // 单表达式赋值，让 en 为 effectively final，lambda 才能引用
        final int en = (v == null) ? 1
                : (v instanceof Boolean) ? (((Boolean) v) ? 1 : 0)
                : ("1".equals(String.valueOf(v)) || "true".equalsIgnoreCase(String.valueOf(v))) ? 1 : 0;
        try {
            DbBusyRetry.retry(() -> holder.executeUpdate(
                    "UPDATE " + DataSourceConfig.ALERT_RULE_TABLE
                            + " SET enabled = ?, update_time = ? WHERE id = ?",
                    Integer.valueOf(en), new Date(), Long.valueOf(id)), 8, 200L);
        } catch (Exception e) {
            Dict d = new Dict();
            d.put("error", "toggle rule failed: " + e.getMessage());
            return ApiResponse.ok(d);
        }
        Dict r = new Dict();
        r.put("id", Long.valueOf(id));
        r.put("enabled", Integer.valueOf(en));
        return ApiResponse.ok(r);
    }

    /**
     * 测试触发：按当前规则在最近一个评估窗口内统计命中数，但不写入事件。
     * 给前端「写规则前先验证能不能命中」用，避免规则上线后才发现条件拼错。
     */
    @GetMapping("/api/alert/rules/{id}/test")
    public Object testRule(@PathVariable("id") long id) {
        List<Dict> rows = holder.executeQuery(
                "SELECT * FROM " + DataSourceConfig.ALERT_RULE_TABLE + " WHERE id = ?", Long.valueOf(id));
        if (rows == null || rows.isEmpty()) {
            Dict d = new Dict();
            d.put("error", "rule not found");
            return ApiResponse.ok(d);
        }
        Dict rule = rows.get(0);
        // 委派给引擎的评估逻辑，保证「测试」与「实际触发」条件完全一致
        top.xtcoder.xtlog.ingest.AlertEngine.MatchResult mr =
                top.xtcoder.xtlog.ingest.AlertEngine.evaluate(holder, appService, rule);
        Dict r = new Dict();
        r.put("hitCount", Long.valueOf(mr.hitCount));
        r.put("sample", mr.sampleMessage == null ? "" : mr.sampleMessage);
        r.put("sampleLevel", mr.sampleLevel == null ? "" : mr.sampleLevel);
        r.put("appId", Long.valueOf(mr.appId));
        r.put("threshold", rule.get("threshold"));
        return ApiResponse.ok(r);
    }

    // ==================== 事件查询与处置 ====================

    @GetMapping("/api/alert/events")
    public Object listEvents(@RequestParam(value = "status", required = false) String status,
                             @RequestParam(value = "ruleId", required = false) String ruleId,
                             @RequestParam(value = "page", defaultValue = "1") int page,
                             @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        if (page <= 0) {
            page = 1;
        }
        if (pageSize <= 0) {
            pageSize = 20;
        }
        if (pageSize > 200) {
            pageSize = 200;
        }

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<Object>();
        if (status != null && status.trim().length() > 0) {
            try {
                where.append(" AND status = ?");
                params.add(Integer.valueOf(Integer.parseInt(status.trim())));
            } catch (Exception ignore) {
                // 非数字忽略
            }
        }
        if (ruleId != null && ruleId.trim().length() > 0) {
            try {
                where.append(" AND rule_id = ?");
                params.add(Long.valueOf(Long.parseLong(ruleId.trim())));
            } catch (Exception ignore) {
                // 非数字忽略
            }
        }

        long total = 0L;
        List<Dict> cnt = holder.executeQuery(
                "SELECT COUNT(*) AS c FROM " + DataSourceConfig.ALERT_EVENT_TABLE + where, params.toArray());
        if (!cnt.isEmpty() && cnt.get(0).get("c") != null) {
            total = ((Number) cnt.get(0).get("c")).longValue();
        }

        String sql = "SELECT id, rule_id, rule_name, app_id, app_name, level, summary, sample_message,"
                + " hit_count, status, first_time, last_time, ack_time, ack_user"
                + " FROM " + DataSourceConfig.ALERT_EVENT_TABLE + where
                + " ORDER BY last_time DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<Object>(params);
        pageParams.add(Integer.valueOf(pageSize));
        pageParams.add(Integer.valueOf((page - 1) * pageSize));
        List<Dict> rows = holder.executeQuery(sql, pageParams.toArray());

        Dict resp = new Dict();
        resp.put("list", rows);
        resp.put("total", Long.valueOf(total));
        resp.put("page", Integer.valueOf(page));
        resp.put("pageSize", Integer.valueOf(pageSize));
        return ApiResponse.ok(resp);
    }

    /** 认领：body 可带 { user: "用户名" }，未传则留空 */
    @PostMapping("/api/alert/events/{id}/ack")
    public Object ackEvent(@PathVariable("id") long id, @RequestBody Dict body) {
        String user = body == null ? "" : strOr(body.get("user"), "");
        try {
            DbBusyRetry.retry(() -> holder.executeUpdate(
                    "UPDATE " + DataSourceConfig.ALERT_EVENT_TABLE
                            + " SET status = 3, ack_time = ?, ack_user = ? WHERE id = ?",
                    new Date(), user, Long.valueOf(id)), 8, 200L);
        } catch (Exception e) {
            Dict d = new Dict();
            d.put("error", "ack failed: " + e.getMessage());
            return ApiResponse.ok(d);
        }
        Dict r = new Dict();
        r.put("acked", Long.valueOf(id));
        return ApiResponse.ok(r);
    }

    /** 手动标记恢复（不删事件，仍可在列表中按 status=2 查到历史） */
    @PostMapping("/api/alert/events/{id}/resolve")
    public Object resolveEvent(@PathVariable("id") long id) {
        try {
            DbBusyRetry.retry(() -> holder.executeUpdate(
                    "UPDATE " + DataSourceConfig.ALERT_EVENT_TABLE + " SET status = 2 WHERE id = ?",
                    Long.valueOf(id)), 8, 200L);
        } catch (Exception e) {
            Dict d = new Dict();
            d.put("error", "resolve failed: " + e.getMessage());
            return ApiResponse.ok(d);
        }
        Dict r = new Dict();
        r.put("resolved", Long.valueOf(id));
        return ApiResponse.ok(r);
    }

    // ==================== 工具 ====================

    private static void appendIfHas(Dict body, String srcKey, String dstCol,
                                    StringBuilder sql, List<Object> params) {
        if (body.containsKey(srcKey)) {
            sql.append(", ").append(dstCol).append(" = ?");
            params.add(body.get(srcKey));
        }
    }

    private static long readLong(Dict body, String key, long def) {
        Object v = body.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int readInt(Dict body, String key, int def) {
        Object v = body.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String strOr(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.length() == 0 ? def : s;
    }
}
