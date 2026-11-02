package top.xtcoder.xtlog.controller;

import java.util.Collections;
import java.util.List;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.web.annotation.GetMapping;
import top.xtcoder.xtboot.web.annotation.PostMapping;
import top.xtcoder.xtboot.web.annotation.RequestBody;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.auth.annotation.SaCheckLogin;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.IngestConfig;

/**
 * 运行期配置：日志保留月数、单条日志字符上限。
 * 保留月数 0 / 不设置 = 不限制；设为 N = 只保留最近 N 个月，更早的月表会立即删除，
 * 此后由 {@code RetentionCleaner} 每 6 小时巡检、跨月自动清理。
 * 字符上限 0 = 不限制（超长日志原样存）；>0 = 超过就拆成多条连续记录。
 */
@RestController
@SaCheckLogin
public class ConfigController {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private IngestConfig ingestConfig;

    @GetMapping("/api/config")
    public Object get() {
        DataSourceConfig.ensureConfigTable(holder);
        List<String> tables = DataSourceConfig.listLogTables(holder);
        Collections.sort(tables);
        Dict d = new Dict();
        d.put("retentionMonths", Integer.valueOf(DataSourceConfig.readRetentionMonths(holder)));
        d.put("maxChars", Integer.valueOf(DataSourceConfig.readMaxChars(holder)));
        d.put("logTables", tables);
        return ApiResponse.ok(d);
    }

    @PostMapping("/api/config")
    public Object save(@RequestBody Dict body) {
        Object m = body.get("retentionMonths");
        int months = 0;
        if (m instanceof Number) {
            months = ((Number) m).intValue();
        } else if (m != null) {
            try {
                months = Integer.parseInt(String.valueOf(m).trim());
            } catch (Exception ignore) {
                months = 0;
            }
        }
        if (months < 0) {
            months = 0;
        }
        // 单条日志字符上限：超过就拆成多条连续记录（见 LogSplitter）。
        // 设太小会把正常日志也切碎，这里给个下限 4096。
        Object mc = body.get("maxChars");
        int maxChars = DataSourceConfig.readMaxChars(holder);
        if (mc != null) {
            int parsed = maxChars;
            try {
                parsed = (int) (mc instanceof Number ? ((Number) mc).longValue()
                        : Long.parseLong(String.valueOf(mc).trim()));
            } catch (Exception ignore) {
                // 解析不了就保留原值
            }
            maxChars = parsed <= 0 ? 0 : Math.max(4096, parsed);
        }
        DataSourceConfig.setConfig(holder, DataSourceConfig.KEY_MAX_CHARS, String.valueOf(maxChars));
        if (ingestConfig != null) {
            ingestConfig.invalidate();
        }
        DataSourceConfig.setConfig(holder, DataSourceConfig.KEY_RETENTION_MONTHS, String.valueOf(months));
        // 保存后立即生效：把超出保留窗口的月表删掉
        List<String> dropped = DataSourceConfig.applyRetention(holder);
        List<String> tables = DataSourceConfig.listLogTables(holder);
        Collections.sort(tables);

        Dict d = new Dict();
        d.put("ok", true);
        d.put("retentionMonths", Integer.valueOf(months));
        d.put("maxChars", Integer.valueOf(maxChars));
        d.put("dropped", dropped);
        d.put("logTables", tables);
        return ApiResponse.ok(d);
    }
}
