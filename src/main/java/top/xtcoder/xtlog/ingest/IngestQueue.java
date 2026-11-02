package top.xtcoder.xtlog.ingest;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.core.Dict;

/**
 * 摄入队列：接入层与落库层之间的有界缓冲。
 *
 * <p>上界用「字节预算」而非「条数」：本系统单条日志的 message 可达 64KB（生产库实测平均 35KB、
 * 最大 7.6MB）。若只按条数封顶（如 20 万条），最坏会在内存里堆出 12GB+ 的字符串，
 * 在 1~2GB 的堆上一遇突发就被 OOM 打挂。改为按「队列内 message 估算字节数」封顶后，
 * 大日志按字节受限，小日志仍能缓存很多条，既不爆堆也不轻易丢数据。</p>
 *
 * <p>队列满时 ERROR 优先保留（阻塞等待），其余级别做短暂背压（{@link #OFFER_WAIT_MS}）后丢弃，
 * 并通过 {@link #getDropped()} 如实上报——保证服务不被日志洪峰打垮，也不会吃掉整个堆。</p>
 */
@Component
public class IngestQueue {

    /**
     * 队列内 message 估算字节数的硬上限（默认 200MB）。
     * 可用 JVM 参数 {@code -Dxtlog.ingest.maxBytes=<字节>} 覆盖，便于按机器内存调。
     * 取值建议：远小于堆（给 FTS / 查询 / 连接池等留余量），例如 2GB 堆设 300~500MB。
     */
    private static final long MAX_BYTES = Long.getLong("xtlog.ingest.maxBytes", 200L * 1024 * 1024);

    /** 条数背压上限（仅防「极小日志无限堆积」的次级兜底，主要约束仍是 MAX_BYTES） */
    private final int maxCount;

    /** 结构上无界、靠字节计数做真实封顶的队列 */
    private final LinkedBlockingQueue<Dict> queue = new LinkedBlockingQueue<Dict>();

    /** 队列内所有 message 的估算字节数（UTF-16 在堆里 2 字节/字符） */
    private final AtomicLong queuedBytes = new AtomicLong(0);

    private final AtomicLong accepted = new AtomicLong(0);
    private final AtomicLong dropped = new AtomicLong(0);

    public IngestQueue() {
        this(50000);
    }

    public IngestQueue(int maxCount) {
        this.maxCount = maxCount;
    }

    public boolean offer(Dict log) {
        long size = estimateBytes(log);
        String level = (String) log.get("level");
        // ERROR 是最高优先级：宁可阻塞也绝不丢弃（关键错误日志不能丢）
        if ("ERROR".equals(level)) {
            try {
                queue.put(log);
                accepted.incrementAndGet();
                queuedBytes.addAndGet(size);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dropped.incrementAndGet();
                return false;
            }
        }
        // 其余级别：字节预算或条数背压超限时直接丢弃。
        // 注意：LinkedBlockingQueue 在结构上无界，不能靠 offer(timeout) 等待空位——
        // 无界队列永远“有空位”，那样会把超限日志照样塞进去，字节预算形同虚设。
        // 所以超限必须主动拒收（返回 false），由上层如实上报 dropped，而非拖垮整个服务。
        if (queuedBytes.get() + size > MAX_BYTES || queue.size() >= maxCount) {
            dropped.incrementAndGet();
            return false;
        }
        // 在预算内：无阻塞入队（LinkedBlockingQueue 结构上无界，真正拦着它的是上面的字节预算）
        if (queue.offer(log)) {
            accepted.incrementAndGet();
            queuedBytes.addAndGet(size);
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    /**
     * 取出最多 max 条交给 writer 落库，并同步扣减字节计数（按同一批 Dict 的估算大小）。
     */
    public int drainTo(List<Dict> batch, int max) {
        int n = queue.drainTo(batch, max);
        if (n > 0) {
            long sum = 0L;
            for (Dict d : batch) {
                sum += estimateBytes(d);
            }
            long v = queuedBytes.addAndGet(-sum);
            if (v < 0L) {
                queuedBytes.set(0L);
            }
        }
        return n;
    }

    /** 估算一条日志在堆里占的字节数（message 是主要项，UTF-16 占 2 字节/字符） */
    private static long estimateBytes(Dict d) {
        if (d == null) {
            return 0L;
        }
        long n = 64L; // Dict/Map 及 level/logger 等元数据的基础开销
        Object m = d.get("message");
        if (m instanceof String) {
            n += ((String) m).length() * 2L;
        } else if (m != null) {
            n += m.toString().length() * 2L;
        }
        return n;
    }

    public int size() {
        return queue.size();
    }

    /** 队列内 message 估算字节数（供监控/接口暴露内存压力） */
    public long queuedBytes() {
        return queuedBytes.get();
    }

    public int getCapacity() {
        return maxCount;
    }

    public long getAccepted() {
        return accepted.get();
    }

    public long getDropped() {
        return dropped.get();
    }

    /**
     * 删除应用时调用：把队列里尚在排队、属于该应用的日志全部丢弃，避免落库线程在
     * 「删完库里行」之后又把这些遗留日志写回、复活成孤儿日志。
     * 实现：一次性 drain 出全部元素，匹配 app_id 的丢弃、其余放回队列（保持顺序大致不变）。
     */
    public void removeByApp(long appId) {
        java.util.List<Dict> all = new java.util.ArrayList<Dict>();
        queue.drainTo(all);
        long freed = 0L;
        int removed = 0;
        for (Dict d : all) {
            Object aid = d.get("app_id");
            boolean match = (aid instanceof Number) && ((Number) aid).longValue() == appId;
            if (match) {
                removed++;
                freed += estimateBytes(d);
            } else {
                queue.offer(d);
            }
        }
        long v = queuedBytes.addAndGet(-freed);
        if (v < 0L) {
            queuedBytes.set(0L);
        }
        if (removed > 0) {
            System.out.println("[xtlog] ingest queue purged " + removed
                    + " pending log(s) for deleted app " + appId);
        }
    }
}
