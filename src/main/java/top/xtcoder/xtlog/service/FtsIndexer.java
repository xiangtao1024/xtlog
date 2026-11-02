package top.xtcoder.xtlog.service;

import java.util.List;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.schedule.XtScheduled;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.ingest.IngestQueue;

/**
 * 全文索引的后台增量回填。
 *
 * <p>索引不跟着写入实时更新——每条日志几十 KB，实时维护倒排索引会把采集链路拖垮。
 * 这里每 10 秒巡检一次：只要某张月表里存在 id 大于水位的行，就按 2MB 文本预算往索引里补，
 * 补到追平或达到单次预算为止。批与批之间 sleep 一小会儿，把写锁让给正在落库的日志，
 * 避免回填把采集线程饿死。
 *
 * <p>回填没追上不影响正确性：查询侧始终把结果拆成「已索引段走 FTS」+「尾段走 LIKE」，
 * 只是尾段越大越慢。所以首次开启后会有一个逐渐变快的过程。
 *
 * <p>优先级：实时落库 > 索引回填。SQLite 只允许一个写者，回填一旦开写事务就会和日志落库抢
 * 同一把排他锁。因此本调度做了两件事：
 *  1）每批只写 2MB（亚秒~1-2 秒即释放锁），且批间留 120ms 空档给落库穿插；
 *  2）一旦发现摄入队列积压超过阈值，本周期立刻收手，把写锁完全让给采集端，
 *     等采集空闲（队列回落）后再继续回填。索引可以慢慢建，日志一秒都不能丢。
 */
@Component
public class FtsIndexer {

    /**
     * 单个巡检周期最多回填多少批。
     * 每批按 2MB 文本预算切分（亚秒~1-2 秒事务），4 批约 2~6 秒，剩下的时间留给日志落库——
     * 写锁是排他的，回填占太久会让采集端吃 SQLITE_BUSY（即便有 busy_timeout 兜底）。
     */
    private static final int MAX_BATCHES_PER_TICK = 4;

    /** 批间让出写入锁的时间 */
    private static final long PAUSE_MS = 120L;

    /**
     * 摄入队列积压超过该值时，本周期停止回填、把写锁让给实时落库。
     * 队列容量 20000，1000 约为 5%——是有意义的「采集端正在忙」信号；低于它说明落库轻松，
     * 回填可穿插进行。阈值之上则完全让路，保证日志零丢失、零延迟堆积。
     */
    private static final int YIELD_BACKLOG = 1000;

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private IngestQueue ingestQueue;

    /** 回填中标记（供状态接口展示，非严格锁：并发巡检本身由调度器保证单线程） */
    private volatile boolean building = false;

    public boolean isBuilding() {
        return building;
    }

    @XtScheduled(fixedRate = 10000)
    public void tick() {
        if (!FtsService.enabled(holder)) {
            building = false;
            return;
        }
        if (building) {
            return; // 上一轮还没跑完，跳过
        }
        building = true;
        try {
            List<String> tables = DataSourceConfig.listLogTables(holder);
            boolean yield = false;
            for (String t : tables) {
                if (!FtsService.enabled(holder)) {
                    break;
                }
                // 实时落库有积压：本周期不再抢写锁，优先保证日志不丢
                if (ingestQueue.size() > YIELD_BACKLOG) {
                    yield = true;
                    break;
                }
                for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
                    long from = FtsService.watermark(holder, t);
                    int n = FtsService.buildBatch(holder, t, from);
                    if (n <= 0) {
                        break; // 这张表追平了
                    }
                    // 批间或回填过程中若采集端开始忙，立即让路
                    if (ingestQueue.size() > YIELD_BACKLOG) {
                        yield = true;
                        break;
                    }
                    if (i < MAX_BATCHES_PER_TICK - 1) {
                        try {
                            Thread.sleep(PAUSE_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (yield) {
                    break;
                }
            }
            if (yield) {
                System.out.println("[xtlog] fts indexer yields to ingestion (backlog="
                        + ingestQueue.size() + ")");
            }
        } catch (Throwable e) {
            System.err.println("[xtlog] fts indexer failed: " + e.getMessage());
        } finally {
            building = false;
        }
    }
}
