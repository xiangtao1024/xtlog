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
        return "jdbc:sqlite:" + dataDir() + "/xtlog.db"
                + "?busy_timeout=5000&journal_mode=WAL&synchronous=NORMAL";
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
        holder.executeUpdate(
                "CREATE TABLE IF NOT EXISTS category ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " name VARCHAR(100) NOT NULL,"
                        + " remark VARCHAR(500),"
                        + " create_time DATETIME,"
                        + " update_time DATETIME)");

        holder.executeUpdate(
                "CREATE TABLE IF NOT EXISTS app ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " category_id INTEGER,"
                        + " name VARCHAR(100) NOT NULL,"
                        + " token VARCHAR(64) NOT NULL,"
                        + " source_type VARCHAR(20) DEFAULT 'HTTP',"
                        + " file_paths TEXT,"
                        + " remark VARCHAR(500),"
                        + " create_time DATETIME,"
                        + " update_time DATETIME)");

        // 迁移：CMD 采集源需要的命令字段（老库可能没有）
        addColumnIfMissing(holder, "app", "cmd", "TEXT");

        // 当前月 + 上个月（跨月边界写入不会失败）
        ensureLogTable(holder, monthTable(System.currentTimeMillis()));
        ensureLogTable(holder, monthTable(System.currentTimeMillis() - 86400000L * 31));
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
        holder.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " app_id INTEGER,"
                        + " category_id INTEGER,"
                        + " level VARCHAR(10),"
                        + " logger VARCHAR(200),"
                        + " thread VARCHAR(100),"
                        + " message TEXT,"
                        + " trace_id VARCHAR(64),"
                        + " src_ip VARCHAR(45),"
                        + " log_time DATETIME,"
                        + " receive_time DATETIME)");
        // 迁移：早期版本的月表没有 src_ip 列（CREATE TABLE IF NOT EXISTS 对已存在的表不会补列），
        // 这里兜底补上，否则文件/命令采集写入的本机 IP 会因「列不存在」直接落库失败。
        addColumnIfMissing(holder, tableName, "src_ip", "VARCHAR(45)");
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
