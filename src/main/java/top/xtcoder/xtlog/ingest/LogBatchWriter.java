package top.xtcoder.xtlog.ingest;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.schedule.XtScheduled;

import top.xtcoder.xtlog.config.DataSourceConfig;

/**
 * 批量落库写者：每 500ms 从摄入队列 drain 一批，按月份分表后调用 DbClient.batchInsert 单事务写入。
 * 用框架的 @XtScheduled 调度，保证 Bean 完全初始化后才执行，规避依赖注入时序问题。
 */
@Component
public class LogBatchWriter {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private IngestQueue queue;

    /** 已确认建过表的月份，避免每条日志都执行一次 DDL */
    private final Set<String> ensuredTables = ConcurrentHashMap.newKeySet();

    @XtScheduled(fixedRate = 500)
    public void flush() {
        List<Dict> batch = new ArrayList<Dict>(1000);
        int n = queue.drainTo(batch, 1000);
        if (n == 0) {
            return;
        }
        // 按月份分表
        Map<String, List<Dict>> groups = new LinkedHashMap<String, List<Dict>>();
        for (Dict d : batch) {
            String table = DataSourceConfig.monthTable(logTimeOf(d));
            List<Dict> g = groups.get(table);
            if (g == null) {
                g = new ArrayList<Dict>();
                groups.put(table, g);
                // 每批只对每个目标月表建一次表
                if (ensuredTables.add(table)) {
                    DataSourceConfig.ensureLogTable(holder, table);
                }
            }
            g.add(d);
        }
        for (Map.Entry<String, List<Dict>> e : groups.entrySet()) {
            try {
                holder.batchInsert(e.getKey(), e.getValue());
            } catch (Exception ex) {
                // 落库失败不应中断其它月表；后续可增强为重试/死信队列
                ex.printStackTrace();
            }
        }
    }

    /**
     * 取日志的业务时间（毫秒）。
     * 注意：入库时 log_time 存的是 java.util.Date，不是 Number，
     * 早期版本按 Number 判定会导致所有日志都落到「当前月」表，历史/补录日志无法正确分表。
     */
    private static long logTimeOf(Dict d) {
        Object ts = d.get("log_time");
        if (ts instanceof Number) {
            return ((Number) ts).longValue();
        }
        if (ts instanceof Date) {
            return ((Date) ts).getTime();
        }
        if (ts != null) {
            try {
                return Long.parseLong(String.valueOf(ts));
            } catch (Exception ignore) {
                // 落到下面兜底
            }
        }
        return System.currentTimeMillis();
    }
}
