package top.xtcoder.xtlog.ingest;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.core.Dict;

/**
 * 摄入队列：接入层与落库层之间的有界缓冲。
 * 队列满时 ERROR 级别优先保留（阻塞等待），其余采样丢弃，保证服务不被日志洪峰打垮。
 */
@Component
public class IngestQueue {

    private final int capacity;
    private final ArrayBlockingQueue<Dict> queue;
    private final AtomicLong accepted = new AtomicLong(0);
    private final AtomicLong dropped = new AtomicLong(0);

    public IngestQueue() {
        this(20000);
    }

    public IngestQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<Dict>(capacity);
    }

    public boolean offer(Dict log) {
        boolean ok = queue.offer(log);
        if (ok) {
            accepted.incrementAndGet();
            return true;
        }
        // 队列满：ERROR 永不丢，阻塞等待空位；其余直接丢弃
        String level = (String) log.get("level");
        if ("ERROR".equals(level)) {
            try {
                queue.put(log);
                accepted.incrementAndGet();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        dropped.incrementAndGet();
        return false;
    }

    public int drainTo(List<Dict> batch, int max) {
        return queue.drainTo(batch, max);
    }

    public int size() {
        return queue.size();
    }

    public int getCapacity() {
        return capacity;
    }

    public long getAccepted() {
        return accepted.get();
    }

    public long getDropped() {
        return dropped.get();
    }
}
