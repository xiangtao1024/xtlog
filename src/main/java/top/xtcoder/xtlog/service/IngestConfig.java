package top.xtcoder.xtlog.service;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.ingest.LogSplitter;

/**
 * 摄入侧运行配置的带 TTL 缓存。
 *
 * <p>单条日志上限在摄入主链路上（每条日志都要判断一次），直接查配置表会平白多出
 * 一倍数据库交互。这里做 5 秒 TTL 的本地缓存：改设置最多 5 秒后生效，
 * 数据源切换后也会自动跟到新库的配置值。
 *
 * <p>读取失败（配置表还没建好等）时回退到 {@link LogSplitter} 系列的默认值，不阻断写入。
 */
@Component
public class IngestConfig {

    /** 缓存有效期：改动后最多延迟这么久在全链路生效 */
    private static final long TTL_MS = 5000L;

    @Autowired
    private DbClientHolder holder;

    private volatile int maxChars = DataSourceConfig.DEFAULT_MAX_CHARS;
    private volatile long syncedAt = 0L;

    /**
     * 当前生效的「单条日志最大字符数」。
     *
     * @return >0 表示超过这个字符数就拆分成多条；0 表示不限制
     */
    public int maxChars() {
        long now = System.currentTimeMillis();
        if (now - syncedAt > TTL_MS) {
            synchronized (this) {
                if (now - syncedAt > TTL_MS) {
                    try {
                        maxChars = DataSourceConfig.readMaxChars(holder);
                    } catch (Exception e) {
                        // 配置表不可用：保持上一次的值，首次失败则用默认值
                    }
                    syncedAt = now;
                }
            }
        }
        return maxChars;
    }

    /** 立刻作废缓存，下次读取重新查库（修改配置后立即生效用） */
    public void invalidate() {
        syncedAt = 0L;
    }
}
