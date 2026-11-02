package top.xtcoder.xtlog.util;

/**
 * SQLite 写锁竞争的轻量重试工具。
 *
 * <p>SQLite 是单写者数据库：同一时刻只允许一个连接持有写锁。后台落库、FTS 回填、前台建应用/改配置
 * 都在抢同一把锁。瞬时竞争是常态，正确做法是「遇 SQLITE_BUSY 短暂退避后重试」，而不是把错误甩给用户
 * （尤其是全局 busy_timeout 较大时，会干等数十秒才报 SQLITE_BUSY）。</p>
 *
 * <p>只在不变量「异常确为锁竞争」时才重试；其它错误（约束冲突、损坏等）立即上抛，避免无意义重试或重复写入。</p>
 */
public final class DbBusyRetry {

    private DbBusyRetry() {
    }

    /** 一个可能抛出受检异常的写操作 */
    @FunctionalInterface
    public interface SqlAction {
        void run() throws Exception;
    }

    /**
     * 在写锁竞争（SQLITE_BUSY / database is locked）时重试。
     *
     * @param action      要执行的写操作（无返回值）
     * @param maxAttempts 最大尝试次数（含首次）
     * @param sleepMs     每次锁竞争后的退避毫秒数
     * @throws Exception  非锁竞争错误，或锁竞争超过 maxAttempts 次仍失败
     */
    public static void retry(SqlAction action, int maxAttempts, long sleepMs) throws Exception {
        if (maxAttempts <= 0) {
            maxAttempts = 1;
        }
        Exception last = null;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                action.run();
                return;
            } catch (Exception e) {
                if (isLockContention(e) && i < maxAttempts - 1) {
                    last = e;
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
        }
        if (last != null) {
            throw last;
        }
    }

    /** 判断异常是否由 SQLite 写锁竞争引起（可安全重试） */
    public static boolean isLockContention(Throwable t) {
        Throwable c = t;
        while (c != null) {
            String m = c.getMessage();
            if (m != null) {
                String lm = m.toLowerCase();
                if (lm.contains("busy") || lm.contains("locked")
                        || lm.contains("sqlite_busy") || lm.contains("database is locked")) {
                    return true;
                }
            }
            c = c.getCause();
        }
        return false;
    }
}
