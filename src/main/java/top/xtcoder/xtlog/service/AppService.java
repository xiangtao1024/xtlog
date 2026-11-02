package top.xtcoder.xtlog.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Service;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;

/**
 * 应用信息缓存：token -> AppInfo、id -> AppInfo 两份索引，避免每条日志都查库。
 * 新建/修改应用后调用 refresh() 重建索引。
 */
@Service
public class AppService {

    @Autowired
    private DbClientHolder holder;

    // 用 volatile + 「先建新 map 再整体替换引用」的方式，保证 refresh() 期间任何并发读者
    // （如 LogBatchWriter 判活）永远看到一份完整一致的索引，而不会在「clear 到 put 完成」之间
    // 把仍存活的应用误判为「已删除」而把在途日志丢掉。
    private volatile Map<String, AppInfo> tokenMap = new HashMap<String, AppInfo>();
    private volatile Map<Long, AppInfo> idMap = new HashMap<Long, AppInfo>();
    /** 是否已加载过。不能用 map.isEmpty() 判断：应用表为空时会退化成「每次调用都查库」 */
    private volatile boolean loaded = false;

    public static class AppInfo {
        public long id;
        public long categoryId;
        public String name;
        public String token;
        public String sourceType;
        public String filePaths;
        /** CMD 采集源：要执行的 shell 命令（如 journalctl -u game.service -f -o cat） */
        public String cmd;
        /** 启用状态：true=启用（接受日志/启动采集），false=停用（拒收推送、不采集）。默认启用。 */
        public boolean enabled = true;
    }

    public synchronized void refresh() {
        loadAll();
    }

    public synchronized void loadAll() {
        // 先建全新 map，再一次性替换 volatile 引用：并发读者只会看到「旧全量」或「新全量」，
        // 不会看到清空中途的半成品，避免 refresh() 期间误判应用已删（见字段说明）。
        Map<String, AppInfo> newToken = new HashMap<String, AppInfo>();
        Map<Long, AppInfo> newId = new HashMap<Long, AppInfo>();
        List<Dict> rows = holder.executeQuery(
                "SELECT id, category_id, name, token, source_type, file_paths, cmd, enabled FROM app");
        for (Dict r : rows) {
            AppInfo info = new AppInfo();
            info.id = ((Number) r.get("id")).longValue();
            Object cid = r.get("category_id");
            info.categoryId = cid == null ? 0L : ((Number) cid).longValue();
            info.name = (String) r.get("name");
            info.token = (String) r.get("token");
            info.sourceType = (String) r.get("source_type");
            info.filePaths = (String) r.get("file_paths");
            info.cmd = (String) r.get("cmd");
            Object en = r.get("enabled");
            // 存量应用 enabled 为 NULL → 视为启用；0=false，其它(含 1) = true
            info.enabled = (en == null) ? true : (toInt(en) != 0);
            if (info.token != null && info.token.length() > 0) {
                newToken.put(info.token, info);
            }
            newId.put(info.id, info);
        }
        tokenMap = newToken;
        idMap = newId;
        loaded = true;
    }

    /** 把各种可能的数值/字符串表示归一成 int（NULL / 非数字 → 0） */
    private static int toInt(Object o) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (Exception ignore) {
                // 落到 0
            }
        }
        return 0;
    }

    public AppInfo resolveByToken(String token) {
        if (token == null) {
            return null;
        }
        if (!loaded) {
            loadAll();
        }
        return tokenMap.get(token);
    }

    public AppInfo resolveById(long id) {
        if (!loaded) {
            loadAll();
        }
        return idMap.get(id);
    }

    /**
     * 遍历所有应用（供文件采集器周期扫描使用）。
     * 返回 idMap 而非 tokenMap：token 为空的应用也应参与文件采集。
     */
    public Collection<AppInfo> getAllApps() {
        if (!loaded) {
            loadAll();
        }
        return idMap.values();
    }
}
