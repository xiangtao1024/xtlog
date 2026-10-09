package top.xtcoder.xtlog.controller;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.AppService;
import top.xtcoder.xtlog.service.AppService.AppInfo;

/**
 * 首页总览：应用数 / 分类数 / 今日日志 / 累计日志 / 级别分布 / 近 7 日趋势 / 今日 Top 应用。
 *
 * <p>日志按月分表（xt_log_entry_YYYYMM），这里把真实存在的月表 UNION ALL 成一张逻辑表再聚合。
 * 趋势用「一次全表扫描 + SUM(CASE WHEN ...)」算出来，避免按天跑 7 次查询。</p>
 */
@RestController
@SaCheckLogin
public class StatsController {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private AppService appService;

    @GetMapping("/api/stats/overview")
    public Object overview() {
        Dict data = new Dict();

        long appCount = countOf("SELECT COUNT(*) AS c FROM app");
        long categoryCount = countOf("SELECT COUNT(*) AS c FROM category");
        data.put("appCount", Long.valueOf(appCount));
        data.put("categoryCount", Long.valueOf(categoryCount));

        long now = System.currentTimeMillis();
        long startOfToday = startOfDay(now);

        List<String> tables = DataSourceConfig.listLogTables(holder);
        if (tables.isEmpty()) {
            // 全新库：建当前月表，保证 UNION 至少有 1 张表，也让后续查询不报错
            String cur = DataSourceConfig.monthTable(now);
            DataSourceConfig.ensureLogTable(holder, cur);
            tables.add(cur);
        }
        data.put("scannedTables", tables.size());

        if (tables.isEmpty()) {
            data.put("todayCount", Long.valueOf(0));
            data.put("totalCount", Long.valueOf(0));
            data.put("levelStats", new ArrayList<Dict>());
            data.put("trend", buildEmptyTrend(now));
            data.put("topApps", new ArrayList<Dict>());
            return ApiResponse.ok(data);
        }

        String union = buildUnion(tables);

        // 1) 今日日志数 + 累计日志数（一次扫描）
        List<Dict> totalRows = holder.executeQuery(
                "SELECT COUNT(*) AS total,"
                        + " SUM(CASE WHEN log_time >= ? THEN 1 ELSE 0 END) AS today"
                        + " FROM (" + union + ") t",
                Long.valueOf(startOfToday));
        long todayCount = 0L;
        long totalCount = 0L;
        if (!totalRows.isEmpty()) {
            Object t = totalRows.get(0).get("today");
            Object c = totalRows.get(0).get("total");
            todayCount = toLong(t);
            totalCount = toLong(c);
        }
        data.put("todayCount", Long.valueOf(todayCount));
        data.put("totalCount", Long.valueOf(totalCount));

        // 2) 今日级别分布
        List<Dict> levelRows = holder.executeQuery(
                "SELECT level, COUNT(*) AS c FROM (" + union + ") t"
                        + " WHERE log_time >= ? GROUP BY level ORDER BY c DESC",
                Long.valueOf(startOfToday));
        List<Dict> levelStats = new ArrayList<Dict>();
        // 前端直接按固定顺序渲染，缺失的级别补 0，避免图表颜色跳变
        String[] ordered = new String[] { "ERROR", "WARN", "INFO", "DEBUG", "TRACE" };
        for (String lv : ordered) {
            Dict d = new Dict();
            d.put("level", lv);
            d.put("count", Long.valueOf(findCount(levelRows, lv)));
            levelStats.add(d);
        }
        for (Dict r : levelRows) {
            String lv = String.valueOf(r.get("level"));
            if (!containsLevel(levelStats, lv)) {
                Dict d = new Dict();
                d.put("level", lv);
                d.put("count", toLongBox(r.get("c")));
                levelStats.add(d);
            }
        }
        data.put("levelStats", levelStats);

        // 3) 近 7 日趋势
        List<Dict> trendRows = queryTrend(union, now);
        data.put("trend", trendRows);

        // 4) 今日 Top 应用
        List<Dict> topRows = holder.executeQuery(
                "SELECT app_id, COUNT(*) AS c FROM (" + union + ") t"
                        + " WHERE log_time >= ? GROUP BY app_id ORDER BY c DESC LIMIT 5",
                Long.valueOf(startOfToday));
        List<Dict> topApps = new ArrayList<Dict>();
        for (Dict r : topRows) {
            long appId = toLong(r.get("app_id"));
            Dict d = new Dict();
            d.put("appId", Long.valueOf(appId));
            AppInfo info = appService.resolveById(appId);
            d.put("appName", info != null ? info.name : ("应用#" + appId));
            d.put("count", toLongBox(r.get("c")));
            topApps.add(d);
        }
        data.put("topApps", topApps);

        return ApiResponse.ok(data);
    }

    /** 7 天趋势：一次扫描 + SUM(CASE WHEN) */
    private List<Dict> queryTrend(String union, long now) {
        StringBuilder sql = new StringBuilder("SELECT ");
        List<Object> params = new ArrayList<Object>();
        List<Long> bounds = new ArrayList<Long>();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        // 往前推 6 天
        c.add(Calendar.DAY_OF_MONTH, -6);
        for (int i = 0; i < 7; i++) {
            long from = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 1);
            long to = c.getTimeInMillis();
            bounds.add(Long.valueOf(from));
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("SUM(CASE WHEN log_time >= ? AND log_time < ? THEN 1 ELSE 0 END) AS d").append(i);
            params.add(Long.valueOf(from));
            params.add(Long.valueOf(to));
        }
        sql.append(" FROM (").append(union).append(") t");

        List<Dict> rows = holder.executeQuery(sql.toString(), params.toArray());
        Dict row = rows.isEmpty() ? new Dict() : rows.get(0);

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd");
        List<Dict> trend = new ArrayList<Dict>();
        for (int i = 0; i < 7; i++) {
            Dict d = new Dict();
            d.put("date", sdf.format(new Date(bounds.get(i).longValue())));
            d.put("count", toLongBox(row.get("d" + i)));
            trend.add(d);
        }
        return trend;
    }

    private List<Dict> buildEmptyTrend(long now) {
        List<Dict> trend = new ArrayList<Dict>();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, -6);
        for (int i = 0; i < 7; i++) {
            Dict d = new Dict();
            d.put("date", sdf.format(c.getTime()));
            d.put("count", Long.valueOf(0));
            trend.add(d);
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return trend;
    }

    /** 把多张月表 UNION ALL 成一张逻辑表 */
    private String buildUnion(List<String> tables) {
        StringBuilder sb = new StringBuilder();
        String cols = "id, app_id, category_id, level, logger, thread, message, trace_id, src_ip, log_time, receive_time";
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                sb.append(" UNION ALL ");
            }
            sb.append("SELECT ").append(cols).append(" FROM ").append(tables.get(i));
        }
        return sb.toString();
    }

    private long countOf(String sql) {
        List<Dict> rows = holder.executeQuery(sql);
        if (rows.isEmpty()) {
            return 0L;
        }
        return toLong(rows.get(0).get("c"));
    }

    private long findCount(List<Dict> rows, String level) {
        for (Dict r : rows) {
            if (level.equals(String.valueOf(r.get("level")))) {
                return toLong(r.get("c"));
            }
        }
        return 0L;
    }

    private boolean containsLevel(List<Dict> stats, String level) {
        for (Dict d : stats) {
            if (level.equals(String.valueOf(d.get("level")))) {
                return true;
            }
        }
        return false;
    }

    private static long startOfDay(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
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

    private static Long toLongBox(Object o) {
        return Long.valueOf(toLong(o));
    }
}
