package top.xtcoder.xtlog.service;

import java.util.Date;
import java.util.List;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Service;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.utils.DigestUtil;

/**
 * 用户服务：账号表的初始化、登录校验、资料维护。
 *
 * <p>密码用框架自带的 {@link DigestUtil} 做「加盐 sha256」存储（格式 {@code salt:hash}），
 * 不存明文。默认管理员 admin / admin123，首次启动时自动写入。</p>
 */
@Service
public class UserService {

    public static final String DEFAULT_USERNAME = "admin";
    public static final String DEFAULT_PASSWORD = "admin123";

    @Autowired
    private DbClientHolder holder;

    /** 建表 + 写入默认管理员（幂等） */
    public void ensureTableAndAdmin() {
        holder.executeUpdate(
                "CREATE TABLE IF NOT EXISTS xt_user ("
                        + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + " username VARCHAR(64) NOT NULL UNIQUE,"
                        + " password VARCHAR(128) NOT NULL,"
                        + " nickname VARCHAR(64),"
                        + " email VARCHAR(128),"
                        + " role VARCHAR(32) DEFAULT 'admin',"
                        + " create_time DATETIME,"
                        + " update_time DATETIME)");

        List<Dict> rows = holder.executeQuery("SELECT COUNT(*) AS c FROM xt_user");
        long count = 0L;
        if (!rows.isEmpty() && rows.get(0).get("c") != null) {
            count = ((Number) rows.get(0).get("c")).longValue();
        }
        if (count == 0) {
            Dict row = new Dict();
            row.put("username", DEFAULT_USERNAME);
            row.put("password", DigestUtil.encryptPassword(DEFAULT_PASSWORD));
            row.put("nickname", "管理员");
            row.put("email", "");
            row.put("role", "admin");
            row.put("create_time", new Date());
            row.put("update_time", new Date());
            holder.insert("xt_user", row);
        }
    }

    public Dict findByUsername(String username) {
        List<Dict> rows = holder.executeQuery(
                "SELECT id, username, password, nickname, email, role, create_time, update_time"
                        + " FROM xt_user WHERE username = ?", username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Dict findById(long id) {
        List<Dict> rows = holder.executeQuery(
                "SELECT id, username, password, nickname, email, role, create_time, update_time"
                        + " FROM xt_user WHERE id = ?", Long.valueOf(id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 校验账号密码。成功返回用户信息（不含 password 字段），失败返回 null。
     */
    public Dict login(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        Dict user = findByUsername(username.trim());
        if (user == null) {
            return null;
        }
        String stored = (String) user.get("password");
        if (!DigestUtil.verifyPassword(password, stored)) {
            return null;
        }
        return safe(user);
    }

    /** 更新昵称 / 邮箱（两者都可只传其一） */
    public void updateProfile(long id, Dict patch) {
        StringBuilder sql = new StringBuilder("UPDATE xt_user SET update_time = ?");
        java.util.List<Object> params = new java.util.ArrayList<Object>();
        params.add(new Date());
        if (patch.get("nickname") != null) {
            sql.append(", nickname = ?");
            params.add(patch.get("nickname"));
        }
        if (patch.get("email") != null) {
            sql.append(", email = ?");
            params.add(patch.get("email"));
        }
        sql.append(" WHERE id = ?");
        params.add(Long.valueOf(id));
        holder.executeUpdate(sql.toString(), params.toArray());
    }

    /**
     * 修改密码：必须先校验旧密码，防止会话被劫持后直接重置。
     *
     * @return true=修改成功；false=旧密码不正确
     */
    public boolean changePassword(long id, String oldPassword, String newPassword) {
        Dict user = findById(id);
        if (user == null || newPassword == null || newPassword.length() < 6) {
            return false;
        }
        if (!DigestUtil.verifyPassword(oldPassword, (String) user.get("password"))) {
            return false;
        }
        holder.executeUpdate("UPDATE xt_user SET password = ?, update_time = ? WHERE id = ?",
                DigestUtil.encryptPassword(newPassword), new Date(), Long.valueOf(id));
        return true;
    }

    /** 剥掉 password 字段后再返回给前端 */
    public static Dict safe(Dict user) {
        Dict d = new Dict();
        d.put("id", user.get("id"));
        d.put("username", user.get("username"));
        d.put("nickname", user.get("nickname"));
        d.put("email", user.get("email"));
        d.put("role", user.get("role"));
        d.put("createTime", user.get("create_time"));
        d.put("updateTime", user.get("update_time"));
        Object nick = d.get("nickname");
        if (nick == null || String.valueOf(nick).length() == 0) {
            d.put("nickname", d.get("username"));
        }
        return d;
    }
}
