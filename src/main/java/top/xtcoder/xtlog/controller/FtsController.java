package top.xtcoder.xtlog.controller;

import java.util.List;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.PostMapping;
import top.xtcoder.xtboot.web.annotation.RequestParam;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.FtsIndexer;
import top.xtcoder.xtlog.service.FtsService;

/**
 * 全文索引（FTS5）的开关与进度。
 *  - GET  /api/fts/status  当前数据源是否支持、开关状态、各月表回填进度
 *  - POST /api/fts/toggle  开 / 关（关闭会立刻删掉索引表释放磁盘）
 */
@RestController
@SaCheckLogin
public class FtsController {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private FtsIndexer indexer;

    @GetMapping("/api/fts/status")
    public Object status() {
        Dict d = new Dict();
        boolean supported = FtsService.supported(holder);
        boolean enabled = FtsService.enabled(holder);
        d.put("supported", Boolean.valueOf(supported));
        d.put("enabled", Boolean.valueOf(enabled));
        d.put("dbType", DataSourceConfig.isMySql(holder) ? "mysql"
                : (DataSourceConfig.isPg(holder) ? "postgresql" : "sqlite"));
        d.put("minLen", Integer.valueOf(FtsService.MIN_LEN));
        d.put("building", Boolean.valueOf(indexer != null && indexer.isBuilding()));
        List<String> tables = DataSourceConfig.listLogTables(holder);
        List<Dict> st = FtsService.status(holder, tables);
        long totalRows = 0L;
        long totalIndexed = 0L;
        for (Dict r : st) {
            totalRows += ((Number) r.get("rows")).longValue();
            totalIndexed += ((Number) r.get("indexed")).longValue();
        }
        d.put("tables", st);
        d.put("totalRows", Long.valueOf(totalRows));
        d.put("totalIndexed", Long.valueOf(totalIndexed));
        d.put("pct", Integer.valueOf(totalRows <= 0 ? 100 : (int) (totalIndexed * 100 / totalRows)));
        d.put("ready", Boolean.valueOf(enabled && totalRows > 0 && totalIndexed >= totalRows));
        return ApiResponse.ok(d);
    }

    /**
     * 开 / 关全文索引。
     * 打开后由 FtsIndexer 每 10 秒增量回填，首轮要把历史日志全部过一遍（3.2GB 文本约 4 分钟），
     * 期间查询仍然正确（未回填部分走 LIKE），只是慢；关掉则立即删除索引表。
     */
    @PostMapping("/api/fts/toggle")
    public Object toggle(@RequestParam(value = "enabled", required = false) String enabled) {
        if (!FtsService.supported(holder)) {
            return ApiResponse.error(400, "当前数据源不支持全文索引（仅 SQLite 支持 FTS5）");
        }
        boolean on = !"0".equals(enabled) && !"false".equalsIgnoreCase(enabled);
        FtsService.setEnabled(holder, on);
        Dict d = new Dict();
        d.put("enabled", Boolean.valueOf(on));
        return ApiResponse.ok(d);
    }
}
