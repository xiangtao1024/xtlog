package top.xtcoder.xtlog.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.schedule.XtScheduled;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.AppService;

/**
 * 批量落库写者：每 250ms 从摄入队列 drain 一批，按月份分表后批量写入。
 * 用框架的 @XtScheduled 调度，保证 Bean 完全初始化后才执行，规避依赖注入时序问题。
 *
 * <p>性能要点：SQLite 路径复用「一条专用连接 + 按表缓存的 PreparedStatement」，并<b>逐组提交</b>
 * （单月表突发等价于一次提交，性能与「整周期单提交」一致，但锁只在单组写入期间被占，不会长时间挡住其它写者）。
 * 这避免了逐批 prepare/commit/close 的开销（早期版本吞吐卡在 ~1.6 万/s 的根因，裸驱动其实能到 ~13 万/s）。
 * 非 SQLite 方言仍走框架批插（自动处理 DATETIME/TIMESTAMP 类型差异）。</p>
 *
 * <p>写锁冲突（SQLite 仅允许一个写者）：全文索引回填、保留策略删表等后台任务也会开写事务，
 * 与本写者抢同一把排他写锁。撞锁时绝不丢日志——整周期未提交的批次重新 offer 回队列，下个周期再试，
 * 直到拿到锁。所以任何锁竞争都只会造成「落库延迟」，不会丢日志。</p>
 */
@Component
public class LogBatchWriter {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private IngestQueue queue;

    @Autowired
    private AppService appService;

    /** 已确认建过表的月份，避免每条日志都执行一次 DDL */
    private final Set<String> ensuredTables = ConcurrentHashMap.newKeySet();

    /** 写锁冲突时的快速重试次数与间隔（耗尽后仍冲突则放回队头，由下个周期兜底重试） */
    private static final int RETRY_TIMES = 3;
    private static final long RETRY_SLEEP_MS = 100L;

    /** 单个写入周期最多落库的条数预算：让突发洪峰快速排空，而非被固定节奏卡死 */
    private static final int MAX_PER_CYCLE = 20000;
    /** 每批从队列 drain 的条数（仅影响内存占用，不影响提交频率） */
    private static final int DRAIN = 1000;

    /**
     * 因写锁冲突（SQLITE_BUSY）本周期没落库成功的批次，连同其目标月表一起放回队头，
     * 优先于新数据在后续周期重试。保证日志「至多延迟、绝不丢失」。
     */
    private final Deque<Pending> retryBuffer = new ArrayDeque<Pending>();

    /** SQLite 专用：一条长期持有的写连接 + 按表缓存的 PreparedStatement，避免逐批 prepare/close */
    private Connection writeConn = null;
    private final Map<String, PreparedStatement> psCache = new LinkedHashMap<String, PreparedStatement>();

    /** 防止调度器并发重入 flush（写连接非线程安全） */
    private final AtomicBoolean writing = new AtomicBoolean(false);

    @XtScheduled(fixedRate = 250)
    public void flush() {
        if (!writing.compareAndSet(false, true)) {
            return; // 上一轮还没跑完，跳过本轮，下一拍再补
        }
        try {
            // 1) 把上次因锁冲突没写进去的重试批次并入本周期（保持先来后到）
            Map<String, List<Dict>> groups = new LinkedHashMap<String, List<Dict>>();
            while (!retryBuffer.isEmpty()) {
                Pending p = retryBuffer.pollFirst();
                // 应用已被删除：其日志不再落库（避免复活成孤儿日志），直接丢弃
                List<Dict> alive = aliveOnly(p.rows);
                if (alive.isEmpty()) {
                    continue;
                }
                List<Dict> g = groups.get(p.table);
                if (g == null) {
                    g = new ArrayList<Dict>();
                    groups.put(p.table, g);
                    if (ensuredTables.add(p.table)) {
                        DataSourceConfig.ensureLogTable(holder, p.table);
                    }
                }
                g.addAll(alive);
            }
            // 2) 正常从摄入队列循环 drain，直到排空或达到本周期预算
            int budget = MAX_PER_CYCLE;
            while (budget > 0) {
                List<Dict> batch = new ArrayList<Dict>(Math.min(DRAIN, budget));
                int n = queue.drainTo(batch, Math.min(DRAIN, budget));
                if (n == 0) {
                    break; // 队列已空，无需继续
                }
                budget -= n;
                for (Dict d : batch) {
                    // 应用已被删除：这条日志丢弃，不再落库（避免复活成孤儿日志）
                    if (!appAlive(d)) {
                        continue;
                    }
                    String table = DataSourceConfig.monthTable(logTimeOf(d));
                    List<Dict> g = groups.get(table);
                    if (g == null) {
                        g = new ArrayList<Dict>();
                        groups.put(table, g);
                        if (ensuredTables.add(table)) {
                            DataSourceConfig.ensureLogTable(holder, table);
                        }
                    }
                    g.add(d);
                }
            }
            if (groups.isEmpty()) {
                return;
            }
            // 3) 落库：SQLite 走专用连接单提交快路径；其余方言走框架批插
            if (DataSourceConfig.isSqlite(holder)) {
                writeGroupsSqlite(groups);
            } else {
                writeGroupsGeneric(groups);
            }
        } finally {
            writing.set(false);
        }
    }

    /**
     * SQLite 专用：复用专用连接 + 按表缓存的 PreparedStatement，逐组 addBatch + executeBatch + commit。
     * <b>每组单独提交</b>而非整周期一次提交——这样写锁只在「单组写入」期间被占，组与组之间释放，
     * 给后台 FTS 回填 / 前台建应用等其它写者留出穿插窗口（早期「整周期长事务」曾让前台写入长时间拿不到锁）。
     *
     * <p>失败处理：
     * <ul>
     *   <li>写锁冲突（SQLITE_BUSY）→ 仅该组重新 offer 回队列（已提交的组不动，绝不重复写入），下个周期再试；</li>
     *   <li>其它错误（数据/约束）→ 记录后放弃该组（与历史语义一致，避免坏数据无限重试）；</li>
     *   <li>任何非 SQL 异常（如 bindRow 里的类型转换）逃逸 → 兜底回滚 + 重置 autoCommit，
     *       并只重入队「尚未提交的组」，<b>杜绝写锁泄漏</b>（否则专用连接会永久持有写锁，拖垮所有写入）。</li>
     * </ul>
     */
    private void writeGroupsSqlite(Map<String, List<Dict>> groups) {
        Connection conn = getWriteConn();
        if (conn == null) {
            reofferAll(groups); // 连不上就退回队列，绝不悄无声息丢掉
            return;
        }
        int committed = 0; // 已成功提交的组数（按迭代顺序），用于逃逸时只重入队未提交组
        try {
            for (Map.Entry<String, List<Dict>> e : groups.entrySet()) {
                try {
                    PreparedStatement ps = getPs(conn, e.getKey());
                    for (Dict d : e.getValue()) {
                        bindRow(ps, d);
                    }
                    ps.executeBatch();
                    ps.clearBatch();
                    conn.commit(); // 每组提交一次：锁只在单组写入期间被占
                    committed++;
                } catch (SQLException ex) {
                    try { conn.rollback(); } catch (Exception ignore) {}
                    closePsCache(); // 该表的 PS 已失效，下个组重建
                    if (isLockContention(ex)) {
                        // 写锁被占：仅本组重入队（已提交的组不动，避免重复），延迟、不丢
                        reoffer(e.getValue());
                        System.err.println("[xtlog] log batch deferred (db lock busy), re-queued "
                                + e.getValue().size() + " rows: " + ex.getMessage());
                    } else {
                        // 非锁竞争：记录后放弃该组（与历史语义一致）
                        System.err.println("[xtlog] log batch insert failed for " + e.getKey() + ": " + ex.getMessage());
                        ex.printStackTrace();
                    }
                }
            }
        } catch (Throwable t) {
            // 兜底：bindRow 等抛出的非 SQL 异常逃逸时，绝不能留下未提交事务（写锁永久泄漏 = 所有写入卡死）
            try { conn.rollback(); } catch (Exception ignore) {}
            try { conn.setAutoCommit(true); conn.setAutoCommit(false); } catch (Exception ignore) {}
            closePsCache();
            // 只重入队「尚未提交的组」（已提交的组绝不重复写入）
            int g = 0;
            for (List<Dict> rows : groups.values()) {
                if (g >= committed) {
                    reoffer(rows);
                }
                g++;
            }
            System.err.println("[xtlog] log batch writer aborted (unexpected): " + t);
        }
    }

    /** 非 SQLite 方言：逐组走框架批插（自动处理类型差异），锁冲突按 RETRY_TIMES 重试 */
    private void writeGroupsGeneric(Map<String, List<Dict>> groups) {
        for (Map.Entry<String, List<Dict>> e : groups.entrySet()) {
            Pending p = new Pending(e.getKey(), e.getValue());
            if (!writeGroupGeneric(p)) {
                reoffer(p.rows); // 锁冲突多次仍失败：退回队列，下个周期再试
            }
        }
    }

    private boolean writeGroupGeneric(Pending p) {
        for (int attempt = 0; attempt < RETRY_TIMES; attempt++) {
            try {
                holder.batchInsert(p.table, p.rows);
                return true;
            } catch (Exception ex) {
                if (isLockContention(ex)) {
                    if (attempt < RETRY_TIMES - 1) {
                        try { Thread.sleep(RETRY_SLEEP_MS); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    } else {
                        return false;
                    }
                } else {
                    System.err.println("[xtlog] log batch insert failed for " + p.table + ": " + ex.getMessage());
                    ex.printStackTrace();
                    return true; // 坏数据放弃，不再重试
                }
            }
        }
        return false;
    }

    /** 取/复用专用写连接；失效则重建并清空 PS 缓存 */
    private Connection getWriteConn() {
        try {
            if (writeConn == null || writeConn.isClosed()) {
                writeConn = holder.getConnection();
                writeConn.setAutoCommit(false);
                // 专用连接用短 busy_timeout：抢不到写锁就快速让步、本周期重入队，
                // 而不是像连接池那样干等全局的 10s。这样落库端永远不会长时间卡死在锁上、
                // 也不会成为「长时间持有写锁的元凶」。
                try (java.sql.Statement st = writeConn.createStatement()) {
                    st.execute("PRAGMA busy_timeout=3000");
                }
                psCache.clear();
            }
            return writeConn;
        } catch (Exception e) {
            System.err.println("[xtlog] writer connection init failed: " + e.getMessage());
            writeConn = null;
            psCache.clear();
            return null;
        }
    }

    /** 取/复用某月表的 PreparedStatement（在专用连接上） */
    private PreparedStatement getPs(Connection conn, String table) throws SQLException {
        PreparedStatement ps = psCache.get(table);
        if (ps == null || ps.isClosed()) {
            ps = conn.prepareStatement("INSERT INTO " + table
                    + " (app_id,category_id,level,logger,thread,message,trace_id,src_ip,log_time,receive_time,cont_group,cont_seq)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
            psCache.put(table, ps);
        }
        return ps;
    }

    private void closePsCache() {
        for (PreparedStatement ps : psCache.values()) {
            try { ps.close(); } catch (Exception ignore) {}
        }
        psCache.clear();
    }

    /** 把一批（同表）日志重新 offer 回摄入队列：仅在本周期落库失败（锁冲突）时调用，保证不丢 */
    private void reoffer(List<Dict> rows) {
        for (Dict d : rows) {
            queue.offer(d);
        }
    }

    private void reofferAll(Map<String, List<Dict>> groups) {
        for (List<Dict> rows : groups.values()) {
            reoffer(rows);
        }
    }

    /** 判断异常是否由 SQLite 写锁竞争引起（可安全重试） */
    private static boolean isLockContention(Throwable t) {
        Throwable c = t;
        while (c != null) {
            if (c.getMessage() != null) {
                String m = c.getMessage().toLowerCase();
                if (m.contains("busy") || m.contains("locked") || m.contains("database is locked")) {
                    return true;
                }
            }
            c = c.getCause();
        }
        return false;
    }

    /** 一组待落库日志 + 其目标月表 */
    private static final class Pending {
        final String table;
        final List<Dict> rows;
        Pending(String table, List<Dict> rows) {
            this.table = table;
            this.rows = rows;
        }
    }

    /**
     * 过滤出「应用仍存在」的日志：应用被删除后，其残留日志不再落库，避免被落库线程
     * 写回库里、复活成孤儿日志。读取 AppService 的缓存是 volatile 读，永远看到完整一致的
     * 索引（refresh 期间不会误判存活应用为已删），见 AppService 字段说明。
     */
    private List<Dict> aliveOnly(List<Dict> rows) {
        List<Dict> keep = new ArrayList<Dict>(rows.size());
        for (Dict d : rows) {
            if (appAlive(d)) {
                keep.add(d);
            }
        }
        return keep;
    }

    /** 单条日志的应用是否仍存在（应用被删 → 返回 false，该日志丢弃） */
    private boolean appAlive(Dict d) {
        Object aid = d.get("app_id");
        if (!(aid instanceof Number)) {
            return true; // 异常数据不在此拦，交给落库层按 app_id=0 处理
        }
        return appService.resolveById(((Number) aid).longValue()) != null;
    }

    /**
     * 取日志的业务时间（毫秒）。入库时 log_time 可能是 Date 或 epoch 毫秒 Number。
     */
    private static long logTimeOf(Dict d) {
        Object ts = d.get("log_time");
        if (ts instanceof Number) return ((Number) ts).longValue();
        if (ts instanceof Date) return ((Date) ts).getTime();
        if (ts != null) {
            try { return Long.parseLong(String.valueOf(ts)); } catch (Exception ignore) {}
        }
        return System.currentTimeMillis();
    }

    /** 把一行日志按固定列顺序填参并 addBatch（不负责 prepare/commit） */
    private static void bindRow(PreparedStatement ps, Dict d) throws SQLException {
        Number aid = (Number) d.get("app_id");
        Number cid = (Number) d.get("category_id");
        ps.setLong(1, aid != null ? aid.longValue() : 0L);
        ps.setLong(2, cid != null ? cid.longValue() : 0L);
        ps.setString(3, str(d.get("level"), "INFO"));
        ps.setString(4, str(d.get("logger"), ""));
        ps.setString(5, str(d.get("thread"), ""));
        ps.setString(6, str(d.get("message"), ""));
        setNullableString(ps, 7, d.get("trace_id"));
        setNullableString(ps, 8, d.get("src_ip"));
        ps.setLong(9, toMs(d.get("log_time")));
        ps.setLong(10, toMs(d.get("receive_time")));
        setNullableString(ps, 11, d.get("cont_group"));
        Object cs = d.get("cont_seq");
        ps.setInt(12, cs instanceof Number ? ((Number) cs).intValue() : 0);
        ps.addBatch();
    }

    private static long toMs(Object v) {
        if (v instanceof Date) return ((Date) v).getTime();
        if (v instanceof Number) return ((Number) v).longValue();
        if (v != null) {
            try { return Long.parseLong(String.valueOf(v)); } catch (Exception ignore) {}
        }
        return System.currentTimeMillis();
    }

    private static void setNullableString(PreparedStatement ps, int idx, Object v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.VARCHAR);
        else ps.setString(idx, String.valueOf(v));
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }
}
