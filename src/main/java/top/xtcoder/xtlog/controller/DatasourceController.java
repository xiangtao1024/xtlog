package top.xtcoder.xtlog.controller;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClient;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.PostMapping;
import top.xtcoder.xtboot.web.annotation.RequestBody;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.AppService;

/**
 * 数据源记录（历史 + 当前）与热切换。
 *
 * <p>解决的问题：切换过数据源之后，原来的库地址没人记得，想切回去只能翻配置重新填一遍。
 * 这里把每次切换过的库（类型 / URL / 账号）登记进 {@code xt_datasource}，
 * 列表里点一下即可切回；记录随库走——迁移时会连同业务表一起拷到目标库，
 * 所以在目标库里照样能看到「从哪迁来的」，一键切回源库。
 *
 * <p>切换沿用 xtboot 的 {@link DbClientHolder#replace(DbClient)} 热替换，无需重启。
 */
@RestController
@SaCheckLogin
public class DatasourceController {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private AppService appService;

    /** 列表接口里密码一律用这个占位符，绝不下发给前端 */
    private static final String PWD_MASK = "***";

    /** 数据源记录列表（当前库排最前，其余按最近切换时间倒序） */
    @GetMapping("/api/datasources")
    public Object list() {
        List<Dict> rows;
        try {
            rows = holder.executeQuery("SELECT * FROM " + DataSourceConfig.DS_TABLE
                    + " ORDER BY is_current DESC, last_switch_time DESC, id DESC");
        } catch (Exception e) {
            rows = new ArrayList<Dict>();
        }
        List<Dict> out = new ArrayList<Dict>();
        for (Dict r : rows) {
            Object p = r.get("password");
            r.put("password", p == null || String.valueOf(p).length() == 0 ? "" : PWD_MASK);
            // 时间统一成 epoch 毫秒下发给前端（MySQL 读出来是 Timestamp，直接序列化格式不可控）
            r.put("create_time", Long.valueOf(DataSourceConfig.tsOf(r.get("create_time"), 0L)));
            r.put("last_switch_time", Long.valueOf(DataSourceConfig.tsOf(r.get("last_switch_time"), 0L)));
            out.add(r);
        }
        return ApiResponse.ok(out);
    }

    /** 按记录 id 切换（凭据取自记录本身，前端不需要也不应该传密码） */
    @PostMapping("/api/datasources/switch")
    public Object switchById(@RequestBody Dict body) {
        Object idv = body.get("id");
        if (idv == null) {
            return ApiResponse.ok(err("缺少记录 id"));
        }
        List<Dict> rows;
        try {
            rows = holder.executeQuery("SELECT * FROM " + DataSourceConfig.DS_TABLE + " WHERE id = ?",
                    Long.valueOf(((Number) idv).longValue()));
        } catch (Exception e) {
            return ApiResponse.ok(err("读取数据源记录失败：" + e.getMessage()));
        }
        if (rows == null || rows.isEmpty()) {
            return ApiResponse.ok(err("记录不存在（可能已被删除）"));
        }
        Dict r = rows.get(0);
        String url = str(r.get("url"));
        if (url.length() == 0) {
            return ApiResponse.ok(err("该记录的 URL 为空"));
        }
        String dbType = str(r.get("db_type"));
        String user = str(r.get("username"));
        String pwd = str(r.get("password"));
        if (PWD_MASK.equals(pwd)) {
            // 理论上不会发生（列表接口才做脱敏），兜底避免把占位符当密码发出去
            pwd = "";
        }
        try {
            doSwitch(holder, appService, dbType, url, user, pwd);
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.ok(err("切换失败：" + rootMsg(e)));
        }
        Dict d = new Dict();
        d.put("ok", true);
        return ApiResponse.ok(d);
    }

    /** 给记录改个好认的名字（默认名字是 URL，长了不好找） */
    @PostMapping("/api/datasources/rename")
    public Object rename(@RequestBody Dict body) {
        Object idv = body.get("id");
        String label = str(body.get("label"));
        if (idv == null) {
            return ApiResponse.ok(err("缺少记录 id"));
        }
        try {
            holder.executeUpdate("UPDATE " + DataSourceConfig.DS_TABLE + " SET label = ? WHERE id = ?",
                    label, Long.valueOf(((Number) idv).longValue()));
        } catch (Exception e) {
            return ApiResponse.ok(err("重命名失败：" + e.getMessage()));
        }
        Dict d = new Dict();
        d.put("ok", true);
        return ApiResponse.ok(d);
    }

    /** 删除一条记录（当前正在用的不允许删） */
    @PostMapping("/api/datasources/remove")
    public Object remove(@RequestBody Dict body) {
        Object idv = body.get("id");
        if (idv == null) {
            return ApiResponse.ok(err("缺少记录 id"));
        }
        long id = ((Number) idv).longValue();
        try {
            List<Dict> rows = holder.executeQuery("SELECT is_current FROM " + DataSourceConfig.DS_TABLE
                    + " WHERE id = ?", Long.valueOf(id));
            if (rows != null && !rows.isEmpty()) {
                Object c = rows.get(0).get("is_current");
                if (c instanceof Number && ((Number) c).intValue() == 1) {
                    return ApiResponse.ok(err("正在使用的数据源不能删除"));
                }
            }
            holder.executeUpdate("DELETE FROM " + DataSourceConfig.DS_TABLE + " WHERE id = ?", Long.valueOf(id));
        } catch (Exception e) {
            return ApiResponse.ok(err("删除失败：" + e.getMessage()));
        }
        Dict d = new Dict();
        d.put("ok", true);
        return ApiResponse.ok(d);
    }

    // ==================== 切换核心（MigrateController 也复用） ====================

    /**
     * 热切换到指定数据源并登记进记录表。
     *
     * <p>顺序很关键：先读旧库的历史 → 再替换数据源 → 在新库建元数据表 →
     * 把历史合并写进新库 → 最后把「当前」这条也登记上。
     * 这样切到全新的空库时，历史不会丢。
     */
    public static void doSwitch(DbClientHolder holder, AppService appService,
                                String dbType, String url, String user, String password) {
        // 1) 切换前把旧库里的历史读出来带走
        List<Dict> carried = new ArrayList<Dict>();
        try {
            List<Dict> rows = holder.executeQuery("SELECT label, db_type, url, username, password, create_time"
                    + " FROM " + DataSourceConfig.DS_TABLE);
            if (rows != null) {
                carried.addAll(rows);
            }
        } catch (Exception ignore) {
            // 旧库没有记录表（老库直连）：忽略，切过去后重新登记即可
        }

        // 2) 目标库若是 MySQL / PostgreSQL 需预建（连接池会剥离 URL 参数，驱动自带建库不生效）
        ensureDatabase(dbType, url, user, password);

        DbClient targetDb = DbClient.create(url, user, password);
        holder.replace(targetDb);
        appService.refresh();
        // 新库可能还没有表结构 / 索引，补齐（幂等：category/app/配置表/当月月表/索引/元数据表）
        DataSourceConfig.initSchema(holder);
        // 持久化，重启后自动连新库
        DataSourceConfig.saveDatasource(url, user, password);

        // 3) 历史合并进新库（按 URL 去重，已存在的不覆盖其名字）
        try {
            holder.executeUpdate("UPDATE " + DataSourceConfig.DS_TABLE + " SET is_current = 0");
        } catch (Exception ignore) {
        }
        long now = System.currentTimeMillis();
        for (Dict r : carried) {
            String u = str(r.get("url"));
            if (u.length() == 0) {
                continue;
            }
            DataSourceConfig.upsertDatasource(holder, str(r.get("label")), str(r.get("db_type")), u,
                    str(r.get("username")), str(r.get("password")),
                    DataSourceConfig.tsOf(r.get("create_time"), now), 0);
        }
        // 4) 登记「当前」这条
        DataSourceConfig.upsertDatasource(holder, null, dbType, url,
                user == null ? "" : user, password == null ? "" : password, now, 1);
    }

    /** 登记一条数据源记录（迁移完成时用来记住目标库，便于之后一键切过去） */
    public static void record(DbClientHolder holder, String dbType, String url, String user, String password) {
        DataSourceConfig.upsertDatasource(holder, null, dbType, url,
                user == null ? "" : user, password == null ? "" : password, System.currentTimeMillis(), 0);
    }

    /**
     * 为 MySQL / PostgreSQL 目标预建数据库（SQLite 用文件，无需）。
     * xtboot 连接池会剥掉 URL 的 query 参数，导致驱动自带的 createDatabaseIfNotExist 不生效。
     */
    public static void ensureDatabase(String dbType, String url, String user, String password) {
        if (!"mysql".equalsIgnoreCase(dbType) && !"postgresql".equalsIgnoreCase(dbType)) {
            return;
        }
        try {
            int q = url.indexOf('?');
            String base = q >= 0 ? url.substring(0, q) : url; // jdbc:xxx://host:port/db
            int lastSlash = base.lastIndexOf('/');
            if (lastSlash < 0) {
                return;
            }
            String db = base.substring(lastSlash + 1);
            String connBase = base.substring(0, lastSlash + 1); // jdbc:xxx://host:port/
            if (db == null || db.length() == 0) {
                return;
            }
            if ("mysql".equalsIgnoreCase(dbType)) {
                String adminUrl = connBase + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
                try (Connection c = java.sql.DriverManager.getConnection(adminUrl, user, password);
                     Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + db + "`");
                }
            } else { // postgresql
                String adminUrl = connBase + "postgres";
                try (Connection c = java.sql.DriverManager.getConnection(adminUrl, user, password);
                     Statement st = c.createStatement()) {
                    java.sql.ResultSet rs = st.executeQuery(
                            "SELECT 1 FROM pg_database WHERE datname = '" + db.replace("'", "''") + "'");
                    if (!rs.next()) {
                        st.executeUpdate("CREATE DATABASE \"" + db.replace("\"", "") + "\"");
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("预建目标数据库失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("预建目标数据库失败：" + e.getMessage(), e);
        }
    }

    private Dict err(String msg) {
        Dict d = new Dict();
        d.put("ok", false);
        d.put("error", msg);
        return d;
    }

    private String rootMsg(Throwable e) {
        Throwable t = e;
        String last = String.valueOf(e);
        int depth = 0;
        while (t != null && depth < 8) {
            String m = t.getMessage();
            if (m != null && m.length() > 0) {
                last = m;
            }
            t = t.getCause();
            depth++;
        }
        return last;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static long num(Object v, long def) {
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    /** URL 归一：去掉查询参数后比较，避免 WAL / 时区等参数差异造成重复登记 */
    private static String norm(String s) {
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
}
