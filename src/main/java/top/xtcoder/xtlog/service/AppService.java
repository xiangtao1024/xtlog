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

    private final Map<String, AppInfo> tokenMap = new HashMap<String, AppInfo>();
    private final Map<Long, AppInfo> idMap = new HashMap<Long, AppInfo>();
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
    }

    public synchronized void refresh() {
        loadAll();
    }

    public synchronized void loadAll() {
        tokenMap.clear();
        idMap.clear();
        loaded = false;
        List<Dict> rows = holder.executeQuery(
                "SELECT id, category_id, name, token, source_type, file_paths, cmd FROM app");
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
            if (info.token != null && info.token.length() > 0) {
                tokenMap.put(info.token, info);
            }
            idMap.put(info.id, info);
        }
        loaded = true;
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
