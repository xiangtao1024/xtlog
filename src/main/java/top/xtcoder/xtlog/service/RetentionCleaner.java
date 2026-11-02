package top.xtcoder.xtlog.service;

import java.util.List;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.schedule.XtScheduled;

import top.xtcoder.xtlog.config.DataSourceConfig;

/**
 * 日志保留期的定期巡检：按「保留 N 个月」配置删除过期的 xt_log_entry_YYYYMM 整表。
 * 只在跨月或配置变更时才会真的删表，平时只是比对表名，开销可忽略。
 */
@Component
public class RetentionCleaner {

    @Autowired
    private DbClientHolder holder;

    /** 每 6 小时巡检一次 */
    @XtScheduled(fixedRate = 6 * 60 * 60 * 1000)
    public void sweep() {
        try {
            List<String> dropped = DataSourceConfig.applyRetention(holder);
            if (!dropped.isEmpty()) {
                System.out.println("[xtlog] retention sweep dropped: " + dropped);
            }
        } catch (Exception e) {
            System.err.println("[xtlog] retention sweep failed: " + e.getMessage());
        }
    }
}
