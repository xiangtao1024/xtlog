package top.xtcoder.xtlog.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
import top.xtcoder.xtlog.service.FtsService;

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

    /** 月表投影字段：回捞整行时用 */
    private static final String COLUMNS =
            "id, app_id, category_id, level, logger, thread, message, trace_id, src_ip, log_time, receive_time,"
                    + " cont_group, cont_seq";

    /**
     * 取回一条超长日志被拆开的所有片段，供界面拼成完整内容。
     *
     * @param g  分片组标识 cont_group
     * @param ts 可选，该组成员的业务时间（毫秒）。带上就只查它所属的那张月表，
     *           省去遍历全部月表；不带则兜底扫所有月表。
     */
    @GetMapping("/api/logs/group")
    public Object group(@RequestParam("g") String g,
                        @RequestParam(value = "ts", required = false) String ts) {
        if (g == null || g.trim().length() == 0) {
            return ApiResponse.ok(Collections.emptyList());
        }
        List<String> tables;
        Long tsLong = parseLong(ts);
        if (tsLong != null && tsLong.longValue() > 0) {
            tables = new ArrayList<String>();
            String mt = DataSourceConfig.monthTable(tsLong.longValue());
            if (DataSourceConfig.listLogTables(holder).contains(mt)) {
                tables.add(mt);
            } else {
                tables = DataSourceConfig.listLogTables(holder);
            }
        } else {
            tables = DataSourceConfig.listLogTables(holder);
        }
        List<Dict> segs = new ArrayList<Dict>();
        for (String tb : tables) {
            List<Dict> rows;
            try {
                rows = holder.executeQuery(
                        "SELECT " + COLUMNS + " FROM " + tb
                                + " WHERE cont_group = ? ORDER BY cont_seq",
                        g.trim());
            } catch (Exception e) {
                // 老月表还没补上分片列（理论上 ensureLogTable 已处理），跳过不影响其它表
                continue;
            }
            if (rows != null) {
                segs.addAll(rows);
            }
        }
        // 跨表理论上不会发生（同一条日志时间戳一致，只会落在一张月表），
        // 兜底按序号排一次，保证拼出来的内容顺序正确。
        Collections.sort(segs, new Comparator<Dict>() {
            @Override
            public int compare(Dict a, Dict b) {
                return toInt(a.get("cont_seq")) - toInt(b.get("cont_seq"));
            }
        });
        StringBuilder all = new StringBuilder();
        for (Dict d : segs) {
            Object m = d.get("message");
            if (m != null) {
                all.append(String.valueOf(m));
            }
        }
        Dict resp = new Dict();
        resp.put("segments", segs);
        resp.put("count", Integer.valueOf(segs.size()));
        resp.put("message", all.toString());
        return ApiResponse.ok(resp);
    }

    /** 单趟最多保留多少条候选行（防超大 OFFSET 打满内存） */
    private static final int MAX_WINDOW = 200000;

    /** 回捞时单个 IN(...) 的占位符上限 */
    private static final int IN_CHUNK = 400;

    /** 昂贵计数（带关键词）的缓存有效期 */
    private static final long COUNT_CACHE_TTL = 60000L;

    /** 昂贵计数缓存最多保留多少条查询签名 */
    private static final int COUNT_CACHE_MAX = 64;

    /** key=查询签名, value={过期时间戳, 总数} */
    private static final Map<String, long[]> COUNT_CACHE = new LinkedHashMap<String, long[]>();

    /** 候选行定位：月表名 + 排序键（log_time, id） */
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
                        @RequestParam(value = "srcIp", required = false) String srcIp,
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
        // 关键词条件不在这里拼：它有两种落法（FTS 倒排索引 / 回退 LIKE），
        // 且两者要拆成两条 SQL 分别取结果（见 keyedRefs / countAll），拼进公共 where 会互相干扰。
        String kw = (keyword == null || keyword.length() == 0) ? null : keyword;
        if (srcIp != null && srcIp.trim().length() > 0) {
            where.append(" AND src_ip = ?");
            params.add(srcIp.trim());
        }
        // 时间比较值按方言给：SQLite 的 log_time 是 epoch 毫秒（Long），
        // MySQL / PG 的 DATETIME / TIMESTAMP 必须给 Timestamp，否则比较永远不成立（查出来 0 条）。
        if (st != null) {
            where.append(" AND log_time >= ?");
            params.add(DataSourceConfig.tsValue(holder, st.longValue()));
        }
        if (et != null) {
            where.append(" AND log_time <= ?");
            params.add(DataSourceConfig.tsValue(holder, et.longValue()));
        }

        Dict resp = new Dict();
        resp.put("scannedTables", tables);

        if (pageMode) {
            // ---- 页码模式：总数 = 各月表 COUNT 之和 ----
            // 不要写成 COUNT over (UNION ALL 全列)：MySQL/PostgreSQL 会把派生表连同 LONGTEXT 一起物化，
            // SQLite 侧也要多走一趟；按月表分别计数后，每张表都能挑到最合适的覆盖索引。
            // 关键词检索无法走索引，COUNT 必须把每张月表的 message 全读一遍（本库 4GB 实测 13~57s）。
            // 开了全文索引后这一步变成毫秒级；没开（或关键词太短用不上索引）时，
            // 翻页会把同一组条件反复求值，这里对「带关键词」的计数做 60s 缓存，后续翻页立刻返回；
            // 不带关键词的计数本来就是毫秒级，不缓存以免遮住实时数据变化。
            boolean useFts = FtsService.canUse(holder, kw);
            long total = countAll(tables, where.toString(), params, kw, useFts && kw != null);
            int from = (pageNo - 1) * size;
            List<Ref> refs = collectRefs(tables, where.toString(), params, clampWindow(from + size), kw, useFts);
            List<Dict> rows = fetchRows(refs, from, size);
            resp.put("list", rows);
            resp.put("total", Long.valueOf(total));
            resp.put("page", Integer.valueOf(pageNo));
            resp.put("pageSize", Integer.valueOf(size));
            resp.put("hasMore", Boolean.valueOf(pageNo * (long) size < total));
            resp.put("fts", Boolean.valueOf(kw == null || useFts));
            resp.put("ftsSkipped", Boolean.valueOf(kw != null && !useFts));
            return ApiResponse.ok(resp);
        }

        // ---- 游标模式 ----
        StringBuilder cwhere = new StringBuilder(where);
        List<Object> cparams = new ArrayList<Object>(params);
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
                cwhere.append(" AND (log_time < ? OR (log_time = ? AND id < ?))");
                Object cur = DataSourceConfig.tsValue(holder, curTime);
                cparams.add(cur);
                cparams.add(cur);
                cparams.add(Long.valueOf(curId));
            }
        }
        boolean cursorFts = FtsService.canUse(holder, kw);
        List<Ref> refs = collectRefs(tables, cwhere.toString(), cparams, limit + 1, kw, cursorFts);
        boolean hasMore = refs.size() > limit;
        List<Dict> rows = fetchRows(refs, 0, limit);
        String nextCursor = null;
        if (!rows.isEmpty()) {
            Dict last = rows.get(rows.size() - 1);
            nextCursor = toLong(last.get("log_time")) + "_" + toLong(last.get("id"));
        }
        resp.put("list", rows);
        resp.put("hasMore", Boolean.valueOf(hasMore));
        resp.put("nextCursor", nextCursor);
        // 前端据此提示「关键词太短没能走索引」，避免用户以为系统坏了
        resp.put("fts", Boolean.valueOf(kw == null || cursorFts));
        resp.put("ftsSkipped", Boolean.valueOf(kw != null && !cursorFts));
        return ApiResponse.ok(resp);
    }

    /** 各月表 COUNT 之和。cacheable=true 时走短 TTL 缓存（仅用于带关键词、且没走成索引的昂贵计数） */
    private long countAll(List<String> tables, String where, List<Object> params, String keyword, boolean cacheable) {
        String key = tables.toString() + "|" + where + "|" + params.toString() + "|kw=" + keyword;
        if (cacheable) {
            synchronized (COUNT_CACHE) {
                long[] hit = COUNT_CACHE.get(key);
                if (hit != null && hit[0] > System.currentTimeMillis()) {
                    return hit[1];
                }
            }
        }
        long total = 0L;
        for (String t : tables) {
            total += countTable(t, where, params, keyword);
        }
        if (cacheable) {
            synchronized (COUNT_CACHE) {
                if (COUNT_CACHE.size() >= COUNT_CACHE_MAX) {
                    Iterator<Map.Entry<String, long[]>> it = COUNT_CACHE.entrySet().iterator();
                    while (it.hasNext()) {
                        if (it.next().getValue()[0] <= System.currentTimeMillis()) {
                            it.remove();
                        }
                    }
                    if (COUNT_CACHE.size() >= COUNT_CACHE_MAX) {
                        COUNT_CACHE.clear();
                    }
                }
                COUNT_CACHE.put(key, new long[] { System.currentTimeMillis() + COUNT_CACHE_TTL, total });
            }
        }
        return total;
    }

    /**
     * 单月表计数。带关键词时按「有没有索引可用」分两种落法：
     *  - 有索引：COUNT = 已索引段（FTS 倒排）+ 未索引尾段（id > 水位，走主键范围，通常是空集）
     *  - 没索引：整表 LIKE 扫描（3.2GB 文本实测 3.5~8.3s，靠上面的 60s 缓存兜底）
     */
    private long countTable(String t, String where, List<Object> params, String keyword) {
        if (keyword == null) {
            return countOne(t, where, params);
        }
        long wm = FtsService.canUse(holder, keyword) ? FtsService.watermark(holder, t) : 0L;
        long sum = 0L;
        if (wm > 0) {
            List<Object> p = new ArrayList<Object>(params);
            p.add(FtsService.matchExpr(keyword));
            sum += countOne(t, where + " AND id IN (SELECT rowid FROM " + FtsService.ftsTableOf(t)
                    + " WHERE message MATCH ?)", p);
        }
        List<Object> p2 = new ArrayList<Object>(params);
        if (wm > 0) {
            // 尾段（id > 水位）＋ 已跳过未进索引的行（超大/损坏，记在 xt_fts_skip）都要用 LIKE 兜住
            p2.add(Long.valueOf(wm));
            p2.add(t);
            p2.add("%" + keyword + "%");
            sum += countOne(t, where + " AND (id > ? OR id IN (SELECT id FROM "
                    + FtsService.SKIP_TABLE + " WHERE t = ?)) AND message LIKE ?", p2);
        } else {
            p2.add("%" + keyword + "%");
            sum += countOne(t, where + " AND message LIKE ?", p2);
        }
        return sum;
    }

    private long countOne(String t, String where, List<Object> params) {
        List<Dict> cnt = holder.executeQuery(
                "SELECT COUNT(1) AS cnt FROM " + t + " WHERE 1=1" + where, params.toArray());
        if (cnt == null || cnt.isEmpty()) {
            return 0L;
        }
        return toLong(cnt.get(0).get("cnt"));
    }

    /**
     * 第一阶段：每张月表各自按索引取「候选行的 id + log_time」，Java 侧归并成全局有序序列。
     *
     * 为什么不直接 UNION ALL 后 ORDER BY：
     *  - 月表是按月分片的表，跨月合并排序只有 1~2 张表参与时要读满全部行；
     *  - 尤其是 message 常常几十 KB（本库平均 35KB、最大 7.6MB），一旦把它带进排序/临时表
     *    就等于把整张 GB 级的表搬一遍。
     * 这里的写法让每张表只走自己的覆盖索引（idx_<t>_time / _apptime / _ip），
     * 投影只有 id 和 log_time，配合 ORDER BY + LIMIT 可以在取满 need 条后立即停下（ early stop ）；
     * MySQL / PostgreSQL 同样受益（不会物化带 LONGTEXT 的派生表）。
     *
     * 带关键词时，每张表再拆成两条 SQL（结果在 Java 侧归并）：
     *  段一 {@code id IN (SELECT rowid FROM <fts> WHERE message MATCH ?)} 走倒排索引；
     *  段二 {@code id > 水位 AND message LIKE ?} 兜住后台还没回填进去的新日志。
     * 拆开写是为了让两段都能各自 early stop——合成一条 OR 会让优化器放弃索引顺序扫描。
     */
    private List<Ref> collectRefs(List<String> tables, String where, List<Object> baseParams, int need,
                                  String keyword, boolean useFts) {
        List<Ref> all = new ArrayList<Ref>();
        String match = useFts ? FtsService.matchExpr(keyword) : null;
        for (String t : tables) {
            long wm = useFts ? FtsService.watermark(holder, t) : 0L;
            if (wm > 0) {
                List<Object> p1 = new ArrayList<Object>(baseParams);
                p1.add(match);
                p1.add(Integer.valueOf(need));
                refsInto(all, t, where + " AND id IN (SELECT rowid FROM " + FtsService.ftsTableOf(t)
                        + " WHERE message MATCH ?)", p1);

                List<Object> p2 = new ArrayList<Object>(baseParams);
                p2.add(Long.valueOf(wm));
                p2.add(t);
                p2.add("%" + keyword + "%");
                p2.add(Integer.valueOf(need));
                refsInto(all, t, where + " AND (id > ? OR id IN (SELECT id FROM "
                        + FtsService.SKIP_TABLE + " WHERE t = ?)) AND message LIKE ?", p2);
                continue;
            }
            List<Object> p = new ArrayList<Object>(baseParams);
            if (keyword != null) {
                p.add("%" + keyword + "%");
            }
            p.add(Integer.valueOf(need));
            refsInto(all, t, keyword == null ? where : where + " AND message LIKE ?", p);
        }
        Collections.sort(all, new Comparator<Ref>() {
            @Override
            public int compare(Ref a, Ref b) {
                if (a.time != b.time) {
                    return (a.time < b.time) ? 1 : -1;
                }
                if (a.id != b.id) {
                    return (a.id < b.id) ? 1 : -1;
                }
                return 0;
            }
        });
        return all;
    }

    /** 单张月表取「候选行 id + log_time」并追加到 all；need 已由调用方放在参数末尾 */
    private void refsInto(List<Ref> all, String t, String where, List<Object> params) {
        List<Dict> rows = holder.executeQuery(
                "SELECT id, log_time FROM " + t + " WHERE 1=1" + where
                        + " ORDER BY log_time DESC, id DESC LIMIT ?",
                params.toArray());
        if (rows == null) {
            return;
        }
        for (Dict r : rows) {
            all.add(new Ref(t, toLong(r.get("log_time")), toLong(r.get("id"))));
        }
    }

    /**
     * 第二阶段：按窗口内的 id 精确回捞整行。
     * 只有最终要返回给前端的这几十行才会去读 message，上一阶段一律不碰大字段。
     */
    private List<Dict> fetchRows(List<Ref> refs, int from, int size) {
        List<Dict> out = new ArrayList<Dict>();
        if (refs.isEmpty() || from < 0 || size <= 0) {
            return out;
        }
        int to = Math.min(refs.size(), from + size);
        List<Ref> window = new ArrayList<Ref>(refs.subList(from, to));

        // 按表归拢同表的 id，减少交互次数
        Map<String, List<Ref>> byTable = new LinkedHashMap<String, List<Ref>>();
        for (Ref r : window) {
            List<Ref> list = byTable.get(r.table);
            if (list == null) {
                list = new ArrayList<Ref>();
                byTable.put(r.table, list);
            }
            list.add(r);
        }
        Map<Long, Dict> fetched = new HashMap<Long, Dict>();
        for (Map.Entry<String, List<Ref>> e : byTable.entrySet()) {
            List<Ref> list = e.getValue();
            int pos = 0;
            // id IN 的参数个数要有上限，避免超深分页时超出各库占位符上限
            while (pos < list.size()) {
                int end = Math.min(list.size(), pos + IN_CHUNK);
                StringBuilder ph = new StringBuilder();
                List<Object> p = new ArrayList<Object>();
                for (int i = pos; i < end; i++) {
                    if (i > pos) {
                        ph.append(",");
                    }
                    ph.append("?");
                    p.add(Long.valueOf(list.get(i).id));
                }
                List<Dict> rows = holder.executeQuery(
                        "SELECT " + COLUMNS + " FROM " + e.getKey() + " WHERE id IN (" + ph + ")", p.toArray());
                if (rows != null) {
                    for (Dict d : rows) {
                        fetched.put(Long.valueOf(toLong(d.get("id"))), d);
                    }
                }
                pos = end;
            }
        }
        // 保持窗口内的排序（可能有行在上一步查询期间被保留策略删掉，缺失就跳过）
        for (Ref r : window) {
            Dict d = fetched.get(Long.valueOf(r.id));
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    /** 单趟最多取多少条候选行，防止超大 OFFSET 把内存打满 */
    private static int clampWindow(int need) {
        if (need <= 0) {
            return MAX_WINDOW;
        }
        return Math.min(need, MAX_WINDOW);
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

    /**
     * 宽松的数值读取：SQLite 返回 Long，MySQL 可能返回 BigDecimal / Long，PG 返回 Long；
     * 时间列（log_time）在 MySQL 上是 {@code java.time.LocalDateTime}（游标也依赖它），
     * 统一走 {@link DataSourceConfig#tsOf(Object, long)} 归一成毫秒，避免 ClassCastException。
     */
    private static long toLong(Object o) {
        return DataSourceConfig.tsOf(o, 0L);
    }

    private static int toInt(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (Exception ignore) {
                // 落到默认 0
            }
        }
        return 0;
    }
}
