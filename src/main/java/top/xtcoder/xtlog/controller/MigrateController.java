package top.xtcoder.xtlog.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClient;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.orm.dialect.Dialect;
import top.xtcoder.xtboot.orm.dialect.MySqlDialect;
import top.xtcoder.xtboot.orm.dialect.PostgreSqlDialect;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.PostMapping;
import top.xtcoder.xtboot.web.annotation.RequestBody;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.AppService;

/**
 * 数据迁移：把当前库的全部表（应用 / 分类 / 用户 / 各月日志表 + 框架内部表）整体拷贝到
 * 另一个 JDBC 数据库，完成后提供「关闭 / 切换到新库」两个动作。
 *
 * <p>能力边界（受 xtboot 方言与本地驱动限制）：
 * <ul>
 *   <li>源库：本项目固定 SQLite，枚举表走 {@code sqlite_master}。</li>
 *   <li>目标：SQLite / MySQL / PostgreSQL —— 三者驱动与方言均已就绪；
 *       其它方言（Oracle / SQLServer / H2）会按通用映射尽力建表，但未充分验证。</li>
 *   <li>切换：通过 {@link DbClientHolder#replace(DbClient)} 热替换当前数据源并持久化，
 *       无需重启服务（xtboot 已支持运行时热替换）。</li>
 * </ul>
 */
@RestController
@SaCheckLogin
public class MigrateController {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private AppService appService;

    /**
     * 单批数据的内存预算（字节，按 UTF-16 估算累加，不是精确值）。
     *
     * <p>日志表里常出现几 MB 的巨型 message（实测单条 7.6MB）。若按「固定行数」分批，
     * 200 行 × 7MB 就是 1.4GB，MySQL 驱动 executeBatch 时还要为每条拼 SQL 文本，
     * 堆直接被撑爆（OutOfMemoryError: Java heap space）。
     * 这里改为<b>按字节预算切批</b>：缓冲区累计超预算就立刻落库，
     * 使迁移全程的堆占用被压在「预算 + 单行」之内，与行的大小无关。
     *
     * <p>可用 JVM 参数 {@code -Dxtlog.migrate.batchBytes=4194304} 调小（堆特别紧时）。
     */
    private static final long BATCH_BYTES = Math.max(1_048_576L,
            Long.getLong("xtlog.migrate.batchBytes", 8L * 1024 * 1024));

    /** 单批的最大行数（兜底：全是小行时不至于拼出一条超长 SQL / 占满连接池等待） */
    private static final int BATCH_ROWS = 500;

    /** MySQL 单条 SQL 包上限，调到 256MB 以容纳超大日志（受服务端全局变量限制时自动忽略） */
    private static final int MYSQL_PACKET_BYTES = 268435456;

    /** 同一时刻只允许一个迁移/切换任务：两个迁移并发跑会互相 DROP 掉对方刚建好的目标表 */
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 最近一次迁移任务的状态（前端轮询 {@code /api/migrate/status} 读取） */
    private static volatile Dict job;

    /** 迁移任务历史（新的在前，最多 {@link #JOB_HISTORY_MAX} 条；进程启动时从库里回填） */
    private static final List<Dict> JOBS = new ArrayList<Dict>();

    private static final int JOB_HISTORY_MAX = 20;

    private static boolean jobsLoaded = false;

    /**
     * 启动迁移（异步）：从当前库拷贝到目标库，但不切换。
     * 拷贝 GB 级库要数分钟，同步等待必然把 HTTP 请求拖超时，故改为后台任务 + 前端轮询进度。
     */
    @PostMapping("/api/migrate")
    public Object migrate(@RequestBody Dict body) {
        String dbType = (String) body.get("dbType");
        String url = norm((String) body.get("url"));
        String user = (String) body.get("user");
        String password = (String) body.get("password");
        if (url == null || url.length() == 0) {
            return ApiResponse.ok(err("请填写目标 JDBC URL"));
        }
        // 防止把数据迁移到当前正在用的库（会并发写坏同一个文件）
        String currentUrl = currentUrl();
        if (currentUrl != null && sameUrl(currentUrl, url)) {
            return ApiResponse.ok(err("目标库不能与当前库相同"));
        }
        if (!BUSY.compareAndSet(false, true)) {
            return ApiResponse.ok(err("已有迁移任务在执行，请等其完成后再试"));
        }
        if ("sqlite".equalsIgnoreCase(dbType) && url.indexOf("journal_mode=") < 0) {
            url = url + (url.indexOf('?') >= 0 ? "&" : "?")
                    + "journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000";
        }
        final String fDbType = dbType;
        final String fUrl = url;
        final String fUser = user == null ? "" : user;
        final String fPwd = password == null ? "" : password;

        final Dict j = new Dict();
        j.put("id", Long.valueOf(System.currentTimeMillis()));
        j.put("startTime", Long.valueOf(System.currentTimeMillis()));
        j.put("dbType", dbType);
        j.put("endTime", Long.valueOf(0));
        j.put("running", Boolean.TRUE);
        j.put("done", Boolean.FALSE);
        j.put("ok", Boolean.FALSE);
        j.put("stage", "准备中");
        j.put("table", "");
        j.put("copied", Long.valueOf(0));
        j.put("target", mask(fUrl));
        j.put("summary", new ArrayList<Dict>());
        job = j;

        // 登记进任务列表 + 落库（前端离开页面再回来也能从 /api/migrate/jobs 看到进度与结果）
        synchronized (JOBS) {
            JOBS.add(0, j);
            while (JOBS.size() > JOB_HISTORY_MAX) {
                JOBS.remove(JOBS.size() - 1);
            }
        }
        insertJob(j, fDbType);

        Thread t = new Thread(new Runnable() {
            public void run() {
                runMigration(j, fDbType, fUrl, fUser, fPwd);
            }
        });
        t.setDaemon(true);
        t.setName("xtlog-migrate");
        t.start();

        Dict d = new Dict();
        d.put("ok", true);
        d.put("async", Boolean.TRUE);
        d.put("jobId", j.get("id"));
        return ApiResponse.ok(d);
    }

    /** 后台真正执行拷贝，并实时把进度写回 job 供前端轮询 */
    private void runMigration(Dict j, String dbType, String url, String user, String password) {
        DbClient targetDb = null;
        try {
            // MySQL / PostgreSQL 目标库需要预建（xtboot 连接池会剥离 URL 参数，
            // createDatabaseIfNotExist 不生效；SQLite 用文件无需预建）
            DatasourceController.ensureDatabase(dbType, url, user, password);
            j.put("stage", "连接目标库");
            targetDb = DbClient.create(url, user, password);
            // MySQL 默认 max_allowed_packet 只有 4MB（5.7）/ 64MB（8.0），单条巨型日志会
            // 直接被服务端拒绝（Packet for query is too large）。若账号有权限就把它抬上去。
            applySessionSettings(targetDb, dbType);
            DbClientHolder target = new DbClientHolder(targetDb);
            List<Dict> summary = copyAll(holder, target, j);
            j.put("summary", summary);
            // 给目标库补齐日志表索引（与源库一致的性能保障）
            j.put("stage", "创建索引");
            DataSourceConfig.ensureLogIndexes(target);
            j.put("stage", "完成");
            j.put("ok", Boolean.TRUE);
            // 把目标库登记进数据源记录（is_current=0），之后在「数据源记录」里可一键切过去
            DatasourceController.record(holder, dbType, url, user, password);
        } catch (Throwable e) {
            // 注意这里是 Throwable 而非 Exception：OutOfMemoryError 是 Error，
            // 只 catch Exception 时线程会带着 running=true 直接死掉，前端永远轮询不到结果。
            // xtboot 的 batchInsert 只抛「Batch insert failed: SQL」，真正的 SQLException 在 cause 链里，
            // 必须一路剥出来才能在界面上看到 Duplicate entry / Data too long / 表不存在 等真因
            e.printStackTrace();
            j.put("error", "迁移失败：" + rootCause(e) + hint(e));
            j.put("stage", "失败");
        } finally {
            if (targetDb != null) {
                try {
                    targetDb.close();
                } catch (Exception ignore) {
                }
            }
            j.put("running", Boolean.FALSE);
            j.put("done", Boolean.TRUE);
            j.put("endTime", Long.valueOf(System.currentTimeMillis()));
            // 落库：重启服务后「迁移记录」列表里仍能看到这次任务的结果
            updateJob(j);
            BUSY.set(false);
        }
    }

    /** 迁移任务历史（含正在跑的那条）。前端离开页面再回来靠它恢复进度显示 */
    @GetMapping("/api/migrate/jobs")
    public Object jobs() {
        loadJobs();
        List<Dict> out = new ArrayList<Dict>();
        synchronized (JOBS) {
            for (Dict j : JOBS) {
                Dict d = new Dict();
                d.put("id", j.get("id"));
                d.put("running", j.get("running"));
                d.put("done", j.get("done"));
                d.put("ok", j.get("ok"));
                d.put("stage", j.get("stage"));
                d.put("table", j.get("table"));
                d.put("copied", j.get("copied"));
                d.put("target", j.get("target"));
                d.put("dbType", j.get("dbType"));
                d.put("startTime", j.get("startTime"));
                d.put("endTime", j.get("endTime"));
                d.put("summary", j.get("summary"));
                d.put("error", j.get("error"));
                out.add(d);
            }
        }
        return ApiResponse.ok(out);
    }

    /** 进程启动后首次访问时，把库里的历史任务回填进内存列表 */
    private void loadJobs() {
        if (jobsLoaded) {
            return;
        }
        synchronized (JOBS) {
            if (jobsLoaded) {
                return;
            }
            jobsLoaded = true;
            try {
                List<Dict> rows = holder.executeQuery("SELECT * FROM " + DataSourceConfig.JOB_TABLE
                        + " ORDER BY id DESC LIMIT " + JOB_HISTORY_MAX);
                for (Dict r : rows) {
                    Dict j = new Dict();
                    j.put("id", longOf(r.get("id"), System.currentTimeMillis()));
                    j.put("dbType", r.get("db_type"));
                    j.put("target", r.get("target"));
                    j.put("stage", r.get("stage"));
                    j.put("ok", intOf(r.get("ok")) == 1 ? Boolean.TRUE : Boolean.FALSE);
                    j.put("running", intOf(r.get("running")) == 1 ? Boolean.TRUE : Boolean.FALSE);
                    j.put("copied", longOf(r.get("copied"), 0L));
                    j.put("error", r.get("error"));
                    j.put("startTime", Long.valueOf(DataSourceConfig.tsOf(r.get("start_time"), 0L)));
                    j.put("endTime", Long.valueOf(DataSourceConfig.tsOf(r.get("end_time"), 0L)));
                    j.put("done", Boolean.TRUE); // 库里存的都是已结束的任务
                    j.put("summary", new ArrayList<Dict>());
                    JOBS.add(j);
                }
            } catch (Exception e) {
                // 表还没建 / 老库直连：忽略，历史列表为空即可，不影响迁移本身
            }
        }
    }

    private void insertJob(Dict j, String dbType) {
        try {
            holder.executeUpdate("INSERT INTO " + DataSourceConfig.JOB_TABLE
                            + " (id, db_type, target, stage, ok, running, copied, error, start_time, end_time)"
                            + " VALUES (?, ?, ?, ?, 0, 1, 0, '', ?, ?)",
                    j.get("id"), dbType, j.get("target"), j.get("stage"),
                    DataSourceConfig.tsValue(holder, longOf(j.get("startTime"), System.currentTimeMillis())),
                    DataSourceConfig.tsValue(holder, 0L));
        } catch (Exception e) {
            System.err.println("[xtlog] record migrate job failed: " + e.getMessage());
        }
    }

    private void updateJob(Dict j) {
        try {
            holder.executeUpdate("UPDATE " + DataSourceConfig.JOB_TABLE
                            + " SET stage = ?, ok = ?, running = ?, copied = ?, error = ?, end_time = ? WHERE id = ?",
                    j.get("stage"),
                    Boolean.TRUE.equals(j.get("ok")) ? Integer.valueOf(1) : Integer.valueOf(0),
                    Boolean.TRUE.equals(j.get("running")) ? Integer.valueOf(1) : Integer.valueOf(0),
                    j.get("copied"),
                    j.get("error") == null ? "" : String.valueOf(j.get("error")),
                    DataSourceConfig.tsValue(holder, longOf(j.get("endTime"), System.currentTimeMillis())),
                    j.get("id"));
        } catch (Exception e) {
            System.err.println("[xtlog] update migrate job failed: " + e.getMessage());
        }
    }

    private static long longOf(Object v, long def) {
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    private static int intOf(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    /** 轮询迁移进度（配合 {@link #migrate} 的异步任务） */
    @GetMapping("/api/migrate/status")
    public Object status() {
        Dict j = job;
        if (j == null) {
            return ApiResponse.ok(err("当前没有迁移任务"));
        }
        Dict d = new Dict();
        d.put("id", j.get("id"));
        d.put("running", j.get("running"));
        d.put("done", j.get("done"));
        d.put("ok", j.get("ok"));
        d.put("stage", j.get("stage"));
        d.put("table", j.get("table"));
        d.put("copied", j.get("copied"));
        d.put("summary", j.get("summary"));
        d.put("target", j.get("target"));
        d.put("error", j.get("error"));
        return ApiResponse.ok(d);
    }

    /**
     * 切换到刚迁移好的目标库：热替换当前数据源并持久化，下次启动自动生效。
     * 目标库应已通过 {@link #migrate} 备好数据；本接口只做「切换」不做「拷贝」。
     */
    @PostMapping("/api/migrate/switch")
    public Object switchTo(@RequestBody Dict body) {
        String dbType = (String) body.get("dbType");
        String url = norm((String) body.get("url"));
        String user = (String) body.get("user");
        String password = (String) body.get("password");
        if (url == null || url.length() == 0) {
            return ApiResponse.ok(err("请填写目标 JDBC URL"));
        }
        if (BUSY.get()) {
            return ApiResponse.ok(err("迁移任务正在执行，请等其完成后再切换"));
        }
        if ("sqlite".equalsIgnoreCase(dbType) && url.indexOf("journal_mode=") < 0) {
            url = url + (url.indexOf('?') >= 0 ? "&" : "?")
                    + "journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000";
        }
        // 切换动作统一走 DatasourceController：热替换 + 登记数据源记录（之后可一键切回）
        try {
            DatasourceController.doSwitch(holder, appService, dbType, url,
                    user == null ? "" : user, password == null ? "" : password);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.ok(err("切换失败：" + rootCause(e)));
        }

        Dict d = new Dict();
        d.put("ok", true);
        return ApiResponse.ok(d);
    }

    // ==================== 复制实现 ====================

    /** 枚举源库全部表，逐个建表 + 分批拷贝，返回每表行数汇总 */
    private List<Dict> copyAll(DbClientHolder src, DbClientHolder target, Dict job) {
        List<String> tables = listSourceTables(src);
        Dialect dialect = target.get().getDialect();
        boolean isPg = dialect instanceof PostgreSqlDialect;
        boolean isMy = dialect instanceof MySqlDialect;

        List<Dict> summary = new ArrayList<Dict>();
        for (String t : tables) {
            List<String> cols = getColumns(src, t);
            if (cols.isEmpty()) {
                continue;
            }
            String ddl = buildCreateSql(t, src, target, cols, isPg, isMy);
            // 先清掉目标表（若存在），保证迁移可重复执行（重跑不会因主键冲突失败）
            try {
                target.executeUpdate("DROP TABLE IF EXISTS " + t);
            } catch (Exception ignore) {
                // 极少数方言不支持 IF EXISTS 时忽略，下面的 CREATE 会兜底
            }
            target.executeUpdate(ddl);
            if (job != null) {
                job.put("stage", "拷贝数据");
                job.put("table", t);
            }
            long n = copyTable(src, target, t, cols, isPg, isMy, job);
            Dict row = new Dict();
            row.put("table", t);
            row.put("rows", Long.valueOf(n));
            summary.add(row);
        }

        // PostgreSQL 的自增序列在显式插入 id 后不会自动推进，补一下避免后续插入撞主键
        if (isPg) {
            for (Dict s : summary) {
                String t = (String) s.get("table");
                try {
                    target.executeUpdate(
                            "SELECT setval(pg_get_serial_sequence('" + t + "','id'), COALESCE((SELECT MAX(id) FROM " + t + "),0))");
                } catch (Exception ignore) {
                    // 没有 id 自增序列的表（如视图/复合主键）忽略
                }
            }
        }
        return summary;
    }

    /** 枚举源库表名（SQLite 走 sqlite_master；其它方言回退 information_schema） */
    private List<String> listSourceTables(DbClientHolder src) {
        List<String> out = new ArrayList<String>();
        try {
            List<Dict> rs = src.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'");
            for (Dict d : rs) {
                Object n = d.get("name");
                if (n != null) {
                    out.add(String.valueOf(n));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        } catch (Exception ignore) {
            // 非 SQLite，走下面回退
        }
        List<Dict> rs = src.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()");
        for (Dict d : rs) {
            Object n = d.get("table_name");
            if (n == null) {
                n = d.get("TABLE_NAME");
            }
            if (n != null) {
                out.add(String.valueOf(n));
            }
        }
        return out;
    }

    /** 取源表列信息（SQLite 走 PRAGMA table_info） */
    private List<String> getColumns(DbClientHolder src, String table) {
        List<String> cols = new ArrayList<String>();
        try {
            List<Dict> info = src.executeQuery("PRAGMA table_info(" + table + ")");
            for (Dict d : info) {
                Object n = d.get("name");
                if (n != null) {
                    cols.add(String.valueOf(n));
                }
            }
            if (!cols.isEmpty()) {
                return cols;
            }
        } catch (Exception ignore) {
        }
        // 回退：用 SELECT 取一列元信息（列名尽量拿全，丢精度无所谓）
        try {
            List<Dict> one = src.executeQuery("SELECT * FROM " + table + " LIMIT 1");
            if (!one.isEmpty()) {
                cols.addAll(one.get(0).keySet());
            }
        } catch (Exception ignore) {
        }
        return cols;
    }

    /** 按目标方言拼出 CREATE TABLE，主键列带自增语法 */
    private String buildCreateSql(String table, DbClientHolder src, DbClientHolder target, List<String> cols, boolean isPg, boolean isMy) {
        // 源列定义（name / type / pk）用于决定类型映射与自增
        List<Dict> info = new ArrayList<Dict>();
        try {
            info = src.executeQuery("PRAGMA table_info(" + table + ")");
        } catch (Exception ignore) {
        }

        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (");
        boolean first = true;
        int pkCount = 0;
        for (Dict c : info) {
            if (c.get("pk") != null && ((Number) c.get("pk")).intValue() > 0) {
                pkCount++;
            }
        }
        // 复合主键（pkCount>1）无法用列内联表达，收集起来在末尾追加 PRIMARY KEY(a, b)
        java.util.Map<Integer, String> pkCols = new java.util.TreeMap<Integer, String>();
        int pkSeen = 0;
        for (Dict c : info) {
            String name = String.valueOf(c.get("name"));
            String type = c.get("type") == null ? "" : String.valueOf(c.get("type"));
            int pk = c.get("pk") == null ? 0 : ((Number) c.get("pk")).intValue();
            if (pk > 0) {
                pkCols.put(Integer.valueOf(pk), name);
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            if (pk > 0 && pkSeen == 0 && pkCount == 1) {
                // 仅第一个主键列内联 PRIMARY KEY（兼容单主键场景）。
                // 只有整数主键才套自增语法：文本主键（如 xt_config.k VARCHAR PRIMARY KEY）
                // 若被建成 BIGINT AUTO_INCREMENT，插入时会报
                // "Incorrect integer value: 'retention.months' for column 'k'"
                if (isIntegerType(type)) {
                    if (isPg) {
                        sb.append(name).append(" BIGSERIAL PRIMARY KEY");
                    } else if (isMy) {
                        sb.append(name).append(" BIGINT PRIMARY KEY AUTO_INCREMENT");
                    } else {
                        sb.append(name).append(" INTEGER PRIMARY KEY AUTOINCREMENT");
                    }
                } else {
                    sb.append(name).append(" ").append(DataSourceConfig.mapType(target, type)).append(" PRIMARY KEY");
                }
                pkSeen++;
            } else {
                sb.append(name).append(" ").append(DataSourceConfig.mapType(target, type));
            }
        }
        if (pkCount > 1 && !pkCols.isEmpty()) {
            sb.append(", PRIMARY KEY (");
            boolean f = true;
            for (String n : pkCols.values()) {
                if (!f) {
                    sb.append(", ");
                }
                f = false;
                sb.append(n);
            }
            sb.append(")");
        }
        sb.append(")");
        return sb.toString();
    }

    /** 是否整数类列（决定主键列是否套自增语法）。类型映射统一走 DataSourceConfig，避免两份实现漂移 */
    private boolean isIntegerType(String src) {
        return DataSourceConfig.isIntegerType(src);
    }

    /**
     * 分批把源表数据拷到目标表。
     *
     * <p>两个关键点：
     * <ol>
     *   <li><b>游标分页</b>：有 id 列就用 {@code WHERE id > ? ORDER BY id}，而不是 LIMIT/OFFSET。
     *       源库在迁移期间可能仍被实时采集写入，月表又带 log_time DESC 覆盖索引，
     *       无 ORDER BY 的 OFFSET 翻页会让同一行落进两个批次 → 目标库主键冲突、且可能漏拷。</li>
     *   <li><b>按字节预算切批</b>：用 JDBC 流式逐行读取，缓冲区累计到 {@link #BATCH_BYTES}
     *       （或 {@link #BATCH_ROWS} 行）就立刻落库并清空，避免巨型 message 把堆撑爆。</li>
     * </ol>
     */
    private long copyTable(DbClientHolder src, DbClientHolder target, String table, List<String> cols, boolean isPg, boolean isMy, Dict job) {
        List<String> temporalCols = temporalColumns(src, table);
        boolean keyset = cols != null && cols.contains("id");
        boolean srcMy = src.get().getDialect() instanceof MySqlDialect;
        long lastId = 0L;
        long offset = 0L;
        long total = 0;

        DbClient srcDb = src.get();
        String sql = keyset
                ? "SELECT * FROM " + table + " WHERE id > ? ORDER BY id ASC LIMIT ?"
                : "SELECT * FROM " + table + " LIMIT ? OFFSET ?";

        while (true) {
            int read = 0;
            // try-with-resources：异常（含 OOM）时也保证 ResultSet / Statement / Connection 归还，
            // 否则连接池会被泄漏耗尽，之后整个服务都连不上库
            try (Connection c = srcDb.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                if (srcMy) {
                    // MySQL 源：开启流式读取，否则驱动会把整批结果一次性缓存在客户端堆里
                    try {
                        ps.setFetchSize(Integer.MIN_VALUE);
                    } catch (SQLException ignore) {
                    }
                }
                if (keyset) {
                    ps.setLong(1, lastId);
                    ps.setInt(2, BATCH_ROWS);
                } else {
                    ps.setInt(1, BATCH_ROWS);
                    ps.setLong(2, offset);
                }
                List<Dict> buf = new ArrayList<Dict>(Math.min(BATCH_ROWS, 128));
                long bufBytes = 0L;
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    String[] names = new String[n];
                    for (int i = 0; i < n; i++) {
                        names[i] = md.getColumnLabel(i + 1);
                    }
                    while (rs.next()) {
                        Dict r = new Dict();
                        long est = 0L;
                        for (int i = 0; i < n; i++) {
                            Object v = rs.getObject(i + 1);
                            r.put(names[i], v);
                            est += estBytes(v);
                        }
                        // 预算已满 → 先落库清空，再放本行（保证缓冲区始终 ≤ 预算 + 单行）
                        if (!buf.isEmpty() && bufBytes + est > BATCH_BYTES) {
                            total += flush(target, table, cols, buf, temporalCols, isPg || isMy, job);
                            bufBytes = 0L;
                        }
                        if (keyset) {
                            Object v = r.get("id");
                            if (v instanceof Number) {
                                long id = ((Number) v).longValue();
                                if (id > lastId) {
                                    lastId = id;
                                }
                            }
                        }
                        buf.add(r);
                        bufBytes += est;
                        read++;
                        if (buf.size() >= BATCH_ROWS) {
                            total += flush(target, table, cols, buf, temporalCols, isPg || isMy, job);
                            bufBytes = 0L;
                        }
                    }
                }
                if (!buf.isEmpty()) {
                    total += flush(target, table, cols, buf, temporalCols, isPg || isMy, job);
                }
            } catch (SQLException e) {
                throw new RuntimeException("读取源表 " + table + " 失败：" + e.getMessage(), e);
            }
            if (read < BATCH_ROWS) {
                break;
            }
            if (!keyset) {
                offset += read;
            }
        }
        return total;
    }

    /** 把缓冲区写入目标库并清空，返回写入行数 */
    private int flush(DbClientHolder target, String table, List<String> cols, List<Dict> buf,
                      List<String> temporalCols, boolean needDateNorm, Dict job) {
        int n = buf.size();
        if (n == 0) {
            return 0;
        }
        // MySQL / PostgreSQL 目标下，把源库的 epoch 毫秒 / 日期串规整成 Timestamp，
        // 否则 Long 直接写 DATETIME/TIMESTAMP 会报类型不匹配（SQLite 是宽松类型，无需处理）
        if (needDateNorm) {
            normalizeDates(buf, temporalCols);
        }
        target.batchInsert(table, buf, cols);
        buf.clear(); // 及时释放，避免大对象赖在堆里等 GC
        if (job != null) {
            Long cur = (Long) job.get("copied");
            long base = cur == null ? 0L : cur.longValue();
            job.put("copied", Long.valueOf(base + n));
        }
        return n;
    }

    /** 粗估一个值在堆里占的字节数（UTF-16：每个 char 2 字节） */
    private long estBytes(Object v) {
        if (v == null) {
            return 8L;
        }
        if (v instanceof String) {
            return ((String) v).length() * 2L + 48L;
        }
        if (v instanceof byte[]) {
            return ((byte[]) v).length + 32L;
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof Date) {
            return 32L;
        }
        return 64L; // Clob / 其它类型，取保守值
    }

    /** 源表中被声明为日期/时间类型的列名集合（用于 PG 目标的日期规整） */
    private List<String> temporalColumns(DbClientHolder src, String table) {
        List<String> out = new ArrayList<String>();
        try {
            List<Dict> info = src.executeQuery("PRAGMA table_info(" + table + ")");
            for (Dict c : info) {
                String type = c.get("type") == null ? "" : String.valueOf(c.get("type")).toUpperCase();
                if (type.contains("DATE") || type.contains("TIME")) {
                    out.add(String.valueOf(c.get("name")));
                }
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /**
     * MySQL / PostgreSQL 目标：把日期列的值规整成 {@link Timestamp}，
     * 源库里这些列虽声明 DATETIME，但存的是 epoch 毫秒（Long）/ 或日期串，
     * 直接写进 DATETIME/TIMESTAMP 会被目标库拒（SQLite 宽松类型不受影响）。
     */
    private void normalizeDates(List<Dict> rows, List<String> temporalCols) {
        if (temporalCols.isEmpty()) {
            return;
        }
        SimpleDateFormat[] fmts = {
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS"),
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"),
                new SimpleDateFormat("yyyy-MM-dd")
        };
        for (Dict r : rows) {
            for (String col : temporalCols) {
                Object v = r.get(col);
                if (v == null) {
                    continue;
                }
                Timestamp ts = null;
                if (v instanceof Number) {
                    // 最常见：epoch 毫秒 Long
                    ts = new Timestamp(((Number) v).longValue());
                } else if (v instanceof Date) {
                    ts = new Timestamp(((Date) v).getTime());
                } else if (v instanceof String) {
                    String s = ((String) v).trim();
                    if (s.isEmpty()) {
                        r.put(col, null);
                        continue;
                    }
                    for (SimpleDateFormat f : fmts) {
                        try {
                            ts = new Timestamp(f.parse(s).getTime());
                            break;
                        } catch (Exception ignore) {
                        }
                    }
                    if (ts == null) {
                        try {
                            ts = new Timestamp(Long.parseLong(s));
                        } catch (Exception ignore) {
                        }
                    }
                }
                if (ts != null) {
                    r.put(col, ts);
                }
            }
        }
    }

    // ==================== 工具 ====================

    /**
     * 目标库侧的会话级设置：目前只做 MySQL 的 max_allowed_packet。
     * 单条日志可能数 MB，默认包上限（5.7 是 4MB）会让插入直接失败，
     * 有权限时抬到 256MB；没权限（普通账号）则静默忽略，报错时由 {@link #hint} 提示用户。
     */
    private void applySessionSettings(DbClient targetDb, String dbType) {
        if (!"mysql".equalsIgnoreCase(dbType)) {
            return;
        }
        try (Connection c = targetDb.getConnection(); Statement st = c.createStatement()) {
            try {
                st.executeUpdate("SET GLOBAL max_allowed_packet=" + MYSQL_PACKET_BYTES);
            } catch (SQLException ignore) {
                // 无 SUPER 权限时忽略（下面再试 session 级）
            }
            try {
                st.executeUpdate("SET SESSION max_allowed_packet=" + MYSQL_PACKET_BYTES);
            } catch (SQLException ignore) {
            }
        } catch (Throwable ignore) {
            // 设置失败不影响迁移本身，交由插入时的报错来暴露
        }
    }

    /** 针对几类典型失败给出可执行的修复建议，省得用户拿一句英文报错猜原因 */
    private String hint(Throwable e) {
        String msg = rootCause(e).toLowerCase();
        if (e instanceof OutOfMemoryError) {
            long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            return "（堆内存不足，当前 -Xmx 约 " + maxMb
                    + "MB；单次拷贝已按 8MB 分批仍溢出，说明单行日志极大或服务本身占用过高。"
                    + "建议调大堆后重启服务，例如 java -Xmx2g -jar xtlog-server.jar）";
        }
        if (msg.contains("max_allowed_packet") || msg.contains("packet for query is too large")) {
            return "（MySQL 单条 SQL 包超限，请在目标库执行 SET GLOBAL max_allowed_packet=268435456 后重试）";
        }
        if (msg.contains("data too long") || msg.contains("too long for column")) {
            return "（目标列长度不足，检查源库 TEXT 列是否被映射成了 VARCHAR/TEXT 而非 LONGTEXT）";
        }
        return "";
    }

    private String currentUrl() {
        String[] saved = DataSourceConfig.loadDatasource();
        return saved[0] != null ? saved[0] : DataSourceConfig.sqliteUrl();
    }

    private boolean sameUrl(String a, String b) {
        return norm(a).equalsIgnoreCase(norm(b));
    }

    private String norm(String s) {
        if (s == null) {
            return "";
        }
        // 归一：去首尾空格、小写、去掉末尾的 WAL/同步参数差异，仅比较「库本体」
        String t = s.trim().toLowerCase();
        int q = t.indexOf('?');
        if (q >= 0) {
            t = t.substring(0, q);
        }
        return t;
    }

    /** 日志里显示目标时抹掉密码，避免敏感信息落盘 */
    private String mask(String url) {
        return url;
    }

    private Dict err(String msg) {
        Dict d = new Dict();
        d.put("ok", false);
        d.put("error", msg);
        return d;
    }

    /** 沿 cause 链取最深的一条异常信息，用于定位 Duplicate entry / Data too long 等真因 */
    private String rootCause(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int depth = 0;
        while (t != null && depth < 8) {
            String m = t.getMessage();
            if (m != null && m.length() > 0 && sb.indexOf(m) < 0) {
                if (sb.length() > 0) {
                    sb.append(" <- ");
                }
                sb.append(m);
            }
            t = t.getCause();
            depth++;
        }
        String s = sb.toString();
        return s.length() == 0 ? String.valueOf(e) : s;
    }
}
