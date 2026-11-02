package top.xtcoder.xtlog.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.RequestParam;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.AppService;
import top.xtcoder.xtlog.service.AppService.AppInfo;

/**
 * 日志分析：按「应用 + 时间范围 + 级别 + 关键词」做多维聚合。
 *
 * <p>执行策略与检索链路一致：**按月表分别聚合，再在 Java 侧合并**。
 * 不要写成 <code>SELECT ... FROM (UNION ALL 全列)</code>——MySQL/PostgreSQL 会把派生表
 * 连同 LONGTEXT 一起物化，代价极高；按月表分开跑，每张表还能各自挑到合适的覆盖索引。</p>
 *
 * <p>时间分桶表达式按方言生成：MySQL 用 DATE_FORMAT、PostgreSQL 用 to_char、
 * SQLite 用 strftime。不能直接对 log_time 做算术——MySQL/PG 里它是 DATETIME/TIMESTAMP，
 * 而 SQLite 里存的是 epoch 毫秒，两种形态不通用。</p>
 */
@RestController
@SaCheckLogin
public class AnalysisController {

    /** 错误聚类时，最多回捞多少条 message 参与归一化（防止大库全扫） */
    private static final int DEFAULT_SCAN = 5000;

    private static final int MAX_SCAN = 50000;

    /** 归一化模板的最大保留长度 */
    private static final int TEMPLATE_MAX = 160;

    /** 回捞 message 时每条最多取多少字符（日志动辄几十 KB，只取开头即可聚类） */
    private static final int HEAD_CHARS = 400;

    private static final String[] LEVEL_ORDER = { "ERROR", "WARN", "INFO", "DEBUG", "TRACE" };

    private static final Pattern P_TS =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}([.,]\\d+)?");
    private static final Pattern P_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern P_HEX = Pattern.compile("\\b0[xX][0-9a-fA-F]+\\b");
    private static final Pattern P_DOTTED = Pattern.compile("\\b\\d+(?:\\.\\d+){1,}\\b");
    private static final Pattern P_NUM = Pattern.compile("\\b\\d+\\b");
    private static final Pattern P_SQ = Pattern.compile("'[^']*'");
    private static final Pattern P_DQ = Pattern.compile("\"[^\"]*\"");
    private static final Pattern P_WS = Pattern.compile("\\s+");

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private AppService appService;

    /**
     * 概览指标：总数、各级别数、错误率、来源 IP 数、logger 数、时间跨度。
     */
    @GetMapping("/api/analysis/overview")
    public Object overview(@RequestParam(value = "appId", required = false) String appId,
                           @RequestParam(value = "from", required = false) String from,
                           @RequestParam(value = "to", required = false) String to,
                           @RequestParam(value = "levels", required = false) String levels,
                           @RequestParam(value = "keyword", required = false) String keyword) {
        try {
            long now = System.currentTimeMillis();
            long f = parseLong(from, now - 86400000L);
            long t = parseLong(to, now);
            if (t < f) {
                long tmp = f;
                f = t;
                t = tmp;
            }

            List<String> tables = resolveTables(f, t);
            Cond c = buildCond(appId, f, t, levels, keyword);

            // 1) 各级别计数：一次 GROUP BY 拿到全部分布，total 由 Java 侧求和
            Map<String, Long> levelMap = new HashMap<String, Long>();
            long total = 0L;
            for (String tb : tables) {
                List<Dict> rows = holder.executeQuery(
                        "SELECT level, COUNT(*) AS c FROM " + tb + c.where + " GROUP BY level",
                        c.params.toArray());
                for (Dict r : rows) {
                    String lv = String.valueOf(r.get("level"));
                    long n = toLong(r.get("c"));
                    Long prev = levelMap.get(lv);
                    levelMap.put(lv, Long.valueOf((prev == null ? 0L : prev.longValue()) + n));
                    total += n;
                }
            }

            // 2) 去重基数 + 时间跨度（同样按月表跑，最后合并：IP/logger 去重在 Java 侧做）
            Set<String> ips = new HashSet<String>();
            Set<String> loggers = new HashSet<String>();
            long minTime = 0L;
            long maxTime = 0L;
            for (String tb : tables) {
                List<Dict> rows = holder.executeQuery(
                        "SELECT src_ip, logger, MIN(log_time) AS mn, MAX(log_time) AS mx FROM " + tb
                                + c.where + " GROUP BY src_ip, logger",
                        c.params.toArray());
                for (Dict r : rows) {
                    String ip = r.get("src_ip") == null ? "" : String.valueOf(r.get("src_ip"));
                    String lg = r.get("logger") == null ? "" : String.valueOf(r.get("logger"));
                    if (ip.length() > 0) {
                        ips.add(ip);
                    }
                    if (lg.length() > 0) {
                        loggers.add(lg);
                    }
                    long mn = toLong(r.get("mn"));
                    long mx = toLong(r.get("mx"));
                    if (mn > 0 && (minTime == 0 || mn < minTime)) {
                        minTime = mn;
                    }
                    if (mx > maxTime) {
                        maxTime = mx;
                    }
                }
            }

            Dict data = new Dict();
            data.put("total", Long.valueOf(total));
            data.put("errorCount", Long.valueOf(levelMap.containsKey("ERROR") ? levelMap.get("ERROR").longValue() : 0L));
            data.put("warnCount", Long.valueOf(levelMap.containsKey("WARN") ? levelMap.get("WARN").longValue() : 0L));
            data.put("errorRate", Double.valueOf(total > 0 ? (double) levelCount(levelMap, "ERROR") / (double) total : 0d));
            data.put("ipCount", Long.valueOf(ips.size()));
            data.put("loggerCount", Long.valueOf(loggers.size()));
            data.put("firstTime", Long.valueOf(minTime));
            data.put("lastTime", Long.valueOf(maxTime));
            data.put("from", Long.valueOf(f));
            data.put("to", Long.valueOf(t));
            data.put("scannedTables", tables);

            // 方便前端直接显示「正在分析哪个应用」
            String appName = "全部应用";
            if (appId != null && appId.trim().length() > 0 && !"all".equalsIgnoreCase(appId.trim())) {
                AppInfo info = appService.resolveById(Long.parseLong(appId.trim()));
                appName = info != null ? info.name : ("应用#" + appId.trim());
            }
            data.put("appName", appName);

            // 级别明细（固定顺序，缺的补 0，前端颜色才不会跳）
            List<Dict> levelStats = new ArrayList<Dict>();
            for (String lv : LEVEL_ORDER) {
                Dict d = new Dict();
                d.put("level", lv);
                d.put("count", Long.valueOf(levelCount(levelMap, lv)));
                levelStats.add(d);
            }
            for (Map.Entry<String, Long> e : levelMap.entrySet()) {
                if (!Arrays.asList(LEVEL_ORDER).contains(e.getKey())) {
                    Dict d = new Dict();
                    d.put("level", e.getKey());
                    d.put("count", e.getValue());
                    levelStats.add(d);
                }
            }
            data.put("levelStats", levelStats);

            return ApiResponse.ok(data);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.error(500, rootCause(e));
        }
    }

    /**
     * 时间趋势：按小时 / 天分桶，返回每个桶的总量与 ERROR / WARN 数。
     */
    @GetMapping("/api/analysis/trend")
    public Object trend(@RequestParam(value = "appId", required = false) String appId,
                        @RequestParam(value = "from", required = false) String from,
                        @RequestParam(value = "to", required = false) String to,
                        @RequestParam(value = "levels", required = false) String levels,
                        @RequestParam(value = "keyword", required = false) String keyword,
                        @RequestParam(value = "bucket", defaultValue = "hour") String bucket) {
        try {
            long now = System.currentTimeMillis();
            long f = parseLong(from, now - 86400000L);
            long t = parseLong(to, now);
            if (t < f) {
                long tmp = f;
                f = t;
                t = tmp;
            }

            List<String> tables = resolveTables(f, t);
            Cond c = buildCond(appId, f, t, levels, keyword);
            String bexpr = bucketExpr(bucket);

            // bucket -> {total, error, warn}
            Map<String, long[]> agg = new TreeMap<String, long[]>();
            for (String tb : tables) {
                List<Dict> rows = holder.executeQuery(
                        "SELECT " + bexpr + " AS b, level, COUNT(*) AS c FROM " + tb + c.where
                                + " GROUP BY b, level",
                        c.params.toArray());
                for (Dict r : rows) {
                    String b = String.valueOf(r.get("b"));
                    long n = toLong(r.get("c"));
                    long[] slot = agg.get(b);
                    if (slot == null) {
                        slot = new long[3];
                        agg.put(b, slot);
                    }
                    slot[0] += n;
                    if ("ERROR".equals(String.valueOf(r.get("level")))) {
                        slot[1] += n;
                    } else if ("WARN".equals(String.valueOf(r.get("level")))) {
                        slot[2] += n;
                    }
                }
            }

            List<Dict> trend = new ArrayList<Dict>();
            for (Map.Entry<String, long[]> e : agg.entrySet()) {
                Dict d = new Dict();
                d.put("label", e.getKey());
                d.put("total", Long.valueOf(e.getValue()[0]));
                d.put("error", Long.valueOf(e.getValue()[1]));
                d.put("warn", Long.valueOf(e.getValue()[2]));
                trend.add(d);
            }

            Dict data = new Dict();
            data.put("bucket", bucket);
            data.put("trend", trend);
            long peakTotal = 0L;
            String peakLabel = "";
            for (Dict d : trend) {
                long v = ((Number) d.get("total")).longValue();
                if (v > peakTotal) {
                    peakTotal = v;
                    peakLabel = String.valueOf(d.get("label"));
                }
            }
            data.put("peakLabel", peakLabel);
            data.put("peakTotal", Long.valueOf(peakTotal));
            return ApiResponse.ok(data);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.error(500, rootCause(e));
        }
    }

    /**
     * 维度分布 Top N：dim = level | logger | ip | thread。
     */
    @GetMapping("/api/analysis/breakdown")
    public Object breakdown(@RequestParam(value = "appId", required = false) String appId,
                            @RequestParam(value = "from", required = false) String from,
                            @RequestParam(value = "to", required = false) String to,
                    @RequestParam(value = "levels", required = false) String levels,
                    @RequestParam(value = "keyword", required = false) String keyword,
                    @RequestParam(value = "dim", defaultValue = "logger") String dim,
                    @RequestParam(value = "limit", defaultValue = "10") int limit) {
        try {
            long now = System.currentTimeMillis();
            long f = parseLong(from, now - 86400000L);
            long t = parseLong(to, now);
            if (t < f) {
                long tmp = f;
                f = t;
                t = tmp;
            }

            String col = dimColumn(dim);
            List<String> tables = resolveTables(f, t);
            Cond c = buildCond(appId, f, t, levels, keyword);
            int lim = limit <= 0 ? 10 : (limit > 100 ? 100 : limit);

            // key -> [count, error]
            Map<String, long[]> agg = new HashMap<String, long[]>();
            for (String tb : tables) {
                List<Dict> rows = holder.executeQuery(
                        "SELECT COALESCE(CAST(" + col + " AS CHAR(200)), '') AS k, COUNT(*) AS c,"
                                + " SUM(CASE WHEN level = 'ERROR' THEN 1 ELSE 0 END) AS e FROM " + tb
                                + c.where + " GROUP BY k",
                        c.params.toArray());
                for (Dict r : rows) {
                    String k = String.valueOf(r.get("k"));
                    if (k.length() == 0) {
                        k = "(空)";
                    }
                    long[] slot = agg.get(k);
                    if (slot == null) {
                        slot = new long[2];
                        agg.put(k, slot);
                    }
                    slot[0] += toLong(r.get("c"));
                    slot[1] += toLong(r.get("e"));
                }
            }

            List<Dict> items = new ArrayList<Dict>();
            for (Map.Entry<String, long[]> e : agg.entrySet()) {
                Dict d = new Dict();
                d.put("key", e.getKey());
                d.put("count", Long.valueOf(e.getValue()[0]));
                d.put("error", Long.valueOf(e.getValue()[1]));
                items.add(d);
            }
            Collections.sort(items, new Comparator<Dict>() {
                public int compare(Dict a, Dict b) {
                    long x = ((Number) a.get("count")).longValue();
                    long y = ((Number) b.get("count")).longValue();
                    return x < y ? 1 : (x > y ? -1 : 0);
                }
            });
            if (items.size() > lim) {
                items = items.subList(0, lim);
            }

            Dict data = new Dict();
            data.put("dim", dim);
            data.put("items", items);
            return ApiResponse.ok(data);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.error(500, rootCause(e));
        }
    }

    /**
     * 错误聚类：把 ERROR / WARN 的 message 归一化成「模板」后聚合，
     * 得到每类错误的次数、首次 / 末次时间、示例原文，便于快速定位高频故障。
     *
     * <p>两阶段执行：先只用索引取候选行的 id（不读 message，避免把 GB 级大文本拉进内存），
     * 再按 id 分批回捞截断后的开头片段。这一点和日志检索链路的做法一致。</p>
     */
    @GetMapping("/api/analysis/patterns")
    public Object patterns(@RequestParam(value = "appId", required = false) String appId,
                           @RequestParam(value = "from", required = false) String from,
                           @RequestParam(value = "to", required = false) String to,
                           @RequestParam(value = "levels", required = false) String levels,
                           @RequestParam(value = "keyword", required = false) String keyword,
                           @RequestParam(value = "limit", defaultValue = "10") int limit,
                           @RequestParam(value = "scan", required = false) String scan) {
        try {
            long now = System.currentTimeMillis();
            long f = parseLong(from, now - 86400000L);
            long t = parseLong(to, now);
            if (t < f) {
                long tmp = f;
                f = t;
                t = tmp;
            }

            int scanRows = scan == null ? DEFAULT_SCAN : (int) parseLong(scan, DEFAULT_SCAN);
            if (scanRows <= 0) {
                scanRows = DEFAULT_SCAN;
            }
            if (scanRows > MAX_SCAN) {
                scanRows = MAX_SCAN;
            }
            int lim = limit <= 0 ? 10 : (limit > 50 ? 50 : limit);

            List<String> tables = resolveTables(f, t);
            Cond c = buildCond(appId, f, t, levels, keyword);

            // ---- 阶段一：只取主键，跨月表归并取最新的 scanRows 条 ----
            List<Ref> refs = new ArrayList<Ref>();
            for (String tb : tables) {
                List<Dict> rows = holder.executeQuery(
                        "SELECT id, log_time FROM " + tb + c.where
                                + " AND level IN ('ERROR', 'WARN') ORDER BY log_time DESC, id DESC LIMIT ?",
                        append(c.params, Integer.valueOf(scanRows)).toArray());
                for (Dict r : rows) {
                    refs.add(new Ref(tb, toLong(r.get("log_time")), toLong(r.get("id"))));
                }
            }
            Collections.sort(refs, new Comparator<Ref>() {
                public int compare(Ref a, Ref b) {
                    if (a.time != b.time) {
                        return a.time > b.time ? -1 : 1;
                }
                    return a.id > b.id ? -1 : (a.id < b.id ? 1 : 0);
                }
            });
            if (refs.size() > scanRows) {
                refs = refs.subList(0, scanRows);
            }

            // ---- 阶段二：按表分组回捞截断片段 ----
            Map<String, List<Long>> byTable = new LinkedHashMap<String, List<Long>>();
            for (Ref r : refs) {
                List<Long> ids = byTable.get(r.table);
                if (ids == null) {
                    ids = new ArrayList<Long>();
                    byTable.put(r.table, ids);
                }
                ids.add(Long.valueOf(r.id));
            }

            List<Sample> samples = new ArrayList<Sample>();
            for (Map.Entry<String, List<Long>> e : byTable.entrySet()) {
                String tb = e.getKey();
                List<Long> ids = e.getValue();
                for (int i = 0; i < ids.size(); i += 400) {
                    int end = Math.min(ids.size(), i + 400);
                    List<Long> chunk = ids.subList(i, end);
                    StringBuilder ph = new StringBuilder();
                    for (int k = 0; k < chunk.size(); k++) {
                        if (k > 0) {
                            ph.append(",");
                        }
                        ph.append("?");
                    }
                    List<Dict> rows = holder.executeQuery(
                            "SELECT id, level, logger, src_ip, log_time, substr(message, 1, " + HEAD_CHARS + ") AS msg"
                                    + " FROM " + tb + " WHERE id IN (" + ph + ")",
                            chunk.toArray());
                    for (Dict r : rows) {
                        Sample s = new Sample();
                        s.id = toLong(r.get("id"));
                        s.level = String.valueOf(r.get("level"));
                        s.logger = r.get("logger") == null ? "" : String.valueOf(r.get("logger"));
                        s.srcIp = r.get("src_ip") == null ? "" : String.valueOf(r.get("src_ip"));
                        s.time = toLong(r.get("log_time"));
                        s.msg = r.get("msg") == null ? "" : String.valueOf(r.get("msg"));
                        samples.add(s);
                    }
                }
            }

            // ---- 归一化 + 聚合 ----
            Map<String, Bucket> buckets = new HashMap<String, Bucket>();
            for (Sample s : samples) {
                String tpl = normalize(firstLine(s.msg));
                if (tpl.length() == 0) {
                    tpl = "(无内容)";
                }
                Bucket b = buckets.get(tpl);
                if (b == null) {
                    b = new Bucket(tpl);
                    buckets.put(tpl, b);
                }
                b.count++;
                if (s.time > 0) {
                    if (b.firstTime == 0 || s.time < b.firstTime) {
                        b.firstTime = s.time;
                    }
                    if (s.time > b.lastTime) {
                        b.lastTime = s.time;
                        b.lastSample = s;
                    }
                }
                if (b.firstSample == null) {
                    b.firstSample = s;
                }
                if ("ERROR".equals(s.level)) {
                    b.errorCount++;
                }
                if (s.logger.length() > 0) {
                    b.loggers.add(s.logger);
                }
                if (s.srcIp.length() > 0) {
                    b.ips.add(s.srcIp);
                }
            }

            List<Dict> items = new ArrayList<Dict>();
            for (Bucket b : buckets.values()) {
                Dict d = new Dict();
                d.put("template", b.template);
                d.put("count", Long.valueOf(b.count));
                d.put("errorCount", Long.valueOf(b.errorCount));
                d.put("firstTime", Long.valueOf(b.firstTime));
                d.put("lastTime", Long.valueOf(b.lastTime));
                d.put("loggers", new ArrayList<String>(b.loggers));
                d.put("ips", new ArrayList<String>(b.ips));
                Sample ex = b.lastSample != null ? b.lastSample : b.firstSample;
                if (ex != null) {
                    Dict sample = new Dict();
                    sample.put("id", Long.valueOf(ex.id));
                    sample.put("level", ex.level);
                    sample.put("logger", ex.logger);
                    sample.put("srcIp", ex.srcIp);
                    sample.put("logTime", Long.valueOf(ex.time));
                    sample.put("message", clip(ex.msg, 1000));
                    d.put("sample", sample);
                }
                items.add(d);
            }
            Collections.sort(items, new Comparator<Dict>() {
                public int compare(Dict a, Dict b) {
                    long x = ((Number) a.get("count")).longValue();
                    long y = ((Number) b.get("count")).longValue();
                    return x < y ? 1 : (x > y ? -1 : 0);
                }
            });
            if (items.size() > lim) {
                items = items.subList(0, lim);
            }

            // 参与聚类的样本总数（用于提示「已扫描 N 条」）
            long scanned = samples.size();
            long totalErrorWarn = 0L;
            for (String tb : tables) {
                totalErrorWarn += countLong(
                        "SELECT COUNT(*) AS c FROM " + tb + c.where + " AND level IN ('ERROR', 'WARN')",
                        c.params.toArray());
            }

            Dict data = new Dict();
            data.put("items", items);
            data.put("scanned", Long.valueOf(scanned));
            data.put("totalErrorWarn", Long.valueOf(totalErrorWarn));
            data.put("scannedTables", tables);
            return ApiResponse.ok(data);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.error(500, rootCause(e));
        }
    }

    // ==================== 查询构造 ====================

    /** 时间范围 -> 与库中真实月表求交集 */
    private List<String> resolveTables(long from, long to) {
        List<String> candidates = DataSourceConfig.monthsBetween(from, to);
        List<String> existing = DataSourceConfig.listLogTables(holder);
        List<String> out = new ArrayList<String>();
        for (String t : candidates) {
            if (existing.contains(t)) {
                out.add(t);
            }
        }
        if (out.isEmpty()) {
            String cur = DataSourceConfig.monthTable(System.currentTimeMillis());
            if (candidates.contains(cur)) {
                DataSourceConfig.ensureLogTable(holder, cur);
                out.add(cur);
            }
        }
        return out;
    }

    /** WHERE 条件与占位参数（自带 " WHERE 1=1"，后续条件一律以 " AND ..." 追加） */
    private Cond buildCond(String appId, long from, long to, String levels, String keyword) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        List<Object> p = new ArrayList<Object>();

        if (appId != null && appId.trim().length() > 0 && !"all".equalsIgnoreCase(appId.trim())) {
            w.append(" AND app_id = ?");
            p.add(Long.valueOf(Long.parseLong(appId.trim())));
        }
        if (levels != null && levels.trim().length() > 0) {
            List<String> lvs = splitList(levels);
            if (!lvs.isEmpty()) {
                StringBuilder ph = new StringBuilder();
                for (int i = 0; i < lvs.size(); i++) {
                    if (i > 0) {
                        ph.append(",");
                    }
                    ph.append("?");
                    p.add(lvs.get(i));
                }
                w.append(" AND level IN (").append(ph).append(")");
            }
        }
        if (keyword != null && keyword.trim().length() > 0) {
            w.append(" AND message LIKE ?");
            p.add("%" + keyword.trim() + "%");
        }
        // 时间比较的值要按方言给：SQLite 的 log_time 是 epoch 毫秒（Long），
        // MySQL / PG 的 DATETIME / TIMESTAMP 必须是 Timestamp，否则比较永远不成立（查出来 0 条）。
        w.append(" AND log_time >= ? AND log_time <= ?");
        p.add(DataSourceConfig.tsValue(holder, from));
        p.add(DataSourceConfig.tsValue(holder, to));

        return new Cond(w.toString(), p);
    }

    private static String dimColumn(String dim) {
        if ("level".equalsIgnoreCase(dim)) {
            return "level";
        }
        if ("ip".equalsIgnoreCase(dim) || "srcIp".equalsIgnoreCase(dim)) {
            return "src_ip";
        }
        if ("thread".equalsIgnoreCase(dim)) {
            return "thread";
        }
        return "logger";
    }

    /** 时间分桶表达式（各库时间列类型不同，不能统一用算术） */
    private String bucketExpr(String bucket) {
        boolean hour = !"day".equalsIgnoreCase(bucket);
        if (DataSourceConfig.isMySql(holder)) {
            return hour ? "DATE_FORMAT(log_time, '%Y-%m-%d %H:00')" : "DATE_FORMAT(log_time, '%Y-%m-%d')";
        }
        if (DataSourceConfig.isPg(holder)) {
            return hour ? "to_char(log_time, 'YYYY-MM-DD HH24:00')" : "to_char(log_time, 'YYYY-MM-DD')";
        }
        return hour
                ? "strftime('%Y-%m-%d %H:00', log_time/1000, 'unixepoch', 'localtime')"
                : "strftime('%Y-%m-%d', log_time/1000, 'unixepoch', 'localtime')";
    }

    private long countLong(String sql, Object... params) {
        List<Dict> rows = holder.executeQuery(sql, params);
        if (rows.isEmpty()) {
            return 0L;
        }
        return toLong(rows.get(0).get("c"));
    }

    /**
     * 宽松的数值读取：SQLite 返回 Long，MySQL 可能返回 BigDecimal / Long，PG 返回 Long；
     * 时间列（log_time 等）在 MySQL 上驱动给的是 {@code java.time.LocalDateTime}，
     * 统一交给 {@link DataSourceConfig#tsOf(Object, long)} 归一成毫秒，避免 ClassCastException。
     */
    private static long toLong(Object o) {
        return DataSourceConfig.tsOf(o, 0L);
    }

    private static long levelCount(Map<String, Long> m, String lv) {
        Long v = m.get(lv);
        return v == null ? 0L : v.longValue();
    }

    private static List<Object> append(List<Object> base, Object extra) {
        List<Object> out = new ArrayList<Object>(base);
        out.add(extra);
        return out;
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.trim().length() == 0) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) {
            return out;
        }
        String[] parts = s.split("[,;\\s]+");
        for (String p : parts) {
            if (p.trim().length() > 0) {
                out.add(p.trim().toUpperCase());
            }
        }
        return out;
    }

    // ==================== 错误模板归一化 ====================

    /** 只取首行——堆栈/多行日志里第一行通常就是错误摘要，聚类效果最好 */
    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        String head = nl >= 0 ? s.substring(0, nl) : s;
        return head.trim();
    }

    /**
     * 把具体数值 / 时间 / ID 抹掉，得到「同一类错误」的模板。
     * 例：<code>User 123 not found at 2026-10-14 10:00:00</code> → <code>User # not found at &lt;ts&gt;</code>
     */
    private static String normalize(String raw) {
        String s = raw == null ? "" : raw;
        s = P_TS.matcher(s).replaceAll("<ts>");
        s = P_UUID.matcher(s).replaceAll("<uuid>");
        s = P_HEX.matcher(s).replaceAll("<hex>");
        s = P_DOTTED.matcher(s).replaceAll("#");
        s = P_NUM.matcher(s).replaceAll("#");
        s = P_SQ.matcher(s).replaceAll("'#'");
        s = P_DQ.matcher(s).replaceAll("\"#\"");
        s = P_WS.matcher(s).replaceAll(" ");
        s = s.trim();
        if (s.length() > TEMPLATE_MAX) {
            s = s.substring(0, TEMPLATE_MAX);
        }
        return s;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        if (m == null || m.length() == 0) {
            m = c.toString();
        }
        return m;
    }

    // ==================== 内部小结构 ====================

    private static final class Cond {
        final String where;
        final List<Object> params;

        Cond(String where, List<Object> params) {
            this.where = where;
            this.params = params;
        }
    }

    private static final class Ref {
        final String table;
        final long time;
        final long id;

        Ref(String table, long time, long id) {
            this.table = table;
            this.time = time;
            this.id = id;
        }
    }

    private static final class Sample {
        long id;
        String level = "";
        String logger = "";
        String srcIp = "";
        long time;
        String msg = "";
    }

    private static final class Bucket {
        final String template;
        long count;
        long errorCount;
        long firstTime;
        long lastTime;
        Sample firstSample;
        Sample lastSample;
        final Set<String> loggers = new HashSet<String>();
        final Set<String> ips = new HashSet<String>();

        Bucket(String template) {
            this.template = template;
        }
    }
}
