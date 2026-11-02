package top.xtcoder.xtlog.controller;

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
 * 初始化向导：首次进入展示当前数据源；可在此把数据源切换到其它 SQLite 文件或 MySQL/PostgreSQL。
 * 切换通过 DbClientHolder.replace 热生效，并重建表结构、刷新应用索引（无需重启）。
 */
@RestController
@SaCheckLogin
public class SetupController {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private AppService appService;

    @GetMapping("/api/setup/status")
    public Object status() {
        Dict d = new Dict();
        d.put("initialized", holder.isReady());
        String[] saved = DataSourceConfig.loadDatasource();
        d.put("dbType", saved[0] == null ? "sqlite" : "custom");
        d.put("path", saved[0] == null ? DataSourceConfig.sqliteUrl() : saved[0]);
        d.put("dataDir", DataSourceConfig.dataDir());
        d.put("persisted", saved[0] != null);
        return ApiResponse.ok(d);
    }

    @PostMapping("/api/setup")
    public Object setup(@RequestBody Dict body) {
        String dbType = (String) body.get("dbType");
        String url = (String) body.get("url");
        String user = (String) body.get("user");
        String password = (String) body.get("password");
        if (url == null || url.length() == 0) {
            Dict e = new Dict();
            e.put("error", "url required");
            return ApiResponse.ok(e);
        }
        // SQLite 时把 PRAGMA 追加到 URL（驱动会对每个连接生效）
        if ("sqlite".equalsIgnoreCase(dbType) && url.indexOf("journal_mode=") < 0) {
            url = url + (url.indexOf('?') >= 0 ? "&" : "?")
                    + "journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000";
        }
        // 统一走 DatasourceController：预建库（MySQL/PG）+ 热替换 + 建表 + 持久化 + 登记数据源记录，
        // 这样这次切换也会留在「数据源记录」里，之后可以一键切回。
        DatasourceController.doSwitch(holder, appService, dbType, url, user, password);

        Dict d = new Dict();
        d.put("ok", true);
        d.put("path", url);
        d.put("persisted", true);
        return ApiResponse.ok(d);
    }
}
