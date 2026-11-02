package top.xtcoder.xtlog.ingest;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.schedule.XtScheduled;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.AppService;

/**
 * 命令采集器：针对 source_type=CMD 的应用，直接执行配置里填写的 shell 命令，
 * 实时读取其 stdout 逐行摄入。典型用途是把 journald 这类「非文件、非 HTTP」的日志
 * 桥接进 xtlog，例如：
 * <pre>
 *   journalctl -u game.service -f -o cat
 *   tail -F /var/log/nginx/access.log
 *   ping -c 1 example.com
 * </pre>
 *
 * 行为要点：
 * - 命令按 OS 选择解释器：Linux/macOS 用 {@code /bin/sh -c}，Windows 用 {@code cmd /c}，
 *   因此支持管道、重定向等 shell 语法（如 {@code journalctl -u x -f -o json | jq -c .}）。
 * - stderr 合并进 stdout，命令自身的报错也会作为日志行被收集，便于排查。
 * - 进程退出后做退避重启（默认 3s），所以 {@code -f} / {@code tail -F} 这类常驻命令
 *   崩溃后会自动拉起；该源类型面向「持续输出」的命令，一次性命令请用 FILE/HTTP。
 * - 应用配置变更（命令改了 / 类型改了 / 被删除）时，reconcile 会停掉旧进程、启新进程，
 *   停止前 flush 残留缓冲，避免最后一条多行日志丢尾。
 */
@Component
public class CommandCollector {

    /** reconcile 周期与最短重启间隔 */
    private static final long RECONCILE_MS = 5000;
    private static final long MIN_RESTART_GAP_MS = 3000;

    @Autowired
    private AppService appService;
    @Autowired
    private LogTextIngestor ingestor;

    /** appId -> 运行态 */
    private final ConcurrentHashMap<Long, Running> running = new ConcurrentHashMap<Long, Running>();
    /** appId -> 上次退出时刻（用于退避重启） */
    private final ConcurrentHashMap<Long, Long> lastExit = new ConcurrentHashMap<Long, Long>();

    @XtScheduled(fixedDelay = RECONCILE_MS)
    public void reconcile() {
        // 1) 拉起缺失 / 命令变更的采集
        for (AppService.AppInfo app : appService.getAllApps()) {
            if (!"CMD".equalsIgnoreCase(app.sourceType)) {
                continue;
            }
            if (!app.enabled) {
                continue; // 已停用：不启动采集
            }
            if (app.cmd == null || app.cmd.trim().length() == 0) {
                continue;
            }
            Running r = running.get(app.id);
            if (r != null && !r.dead && r.cmd.equals(app.cmd)) {
                continue; // 正常运行中
            }
            if (r != null) {
                stop(app.id);
            }
            Long last = lastExit.get(app.id);
            if (last != null && System.currentTimeMillis() - last < MIN_RESTART_GAP_MS) {
                continue; // 退太快，下个周期再拉
            }
            start(app);
        }
        // 2) 清理已删除 / 改类型 / 改命令的残留进程
        for (Long id : new ArrayList<Long>(running.keySet())) {
            AppService.AppInfo app = appService.resolveById(id);
            Running r = running.get(id);
            boolean stale = app == null
                    || !app.enabled
                    || !"CMD".equalsIgnoreCase(app.sourceType)
                    || app.cmd == null
                    || r == null
                    || !r.cmd.equals(app.cmd);
            if (stale) {
                stop(id);
            }
        }
    }

    private void start(AppService.AppInfo app) {
        try {
            String[] cmdArr = buildCmd(app.cmd);
            ProcessBuilder pb = new ProcessBuilder(cmdArr);
            pb.redirectErrorStream(true); // stderr 合并进 stdout，错误也可见
            pb.directory(new File(DataSourceConfig.dataDir()));
            Process p = pb.start();
            Running r = new Running(app.cmd, p);
            running.put(app.id, r);
            lastExit.remove(app.id);
            Thread t = new Thread(() -> pump(app, p), "xtlog-cmd-" + app.id);
            t.setDaemon(true);
            t.start();
            System.out.println("[xtlog] CMD collector started: app=" + app.id
                    + " cmd=\"" + app.cmd + "\"");
        } catch (Exception e) {
            System.err.println("[xtlog] CMD collector start failed app=" + app.id
                    + " cmd=\"" + app.cmd + "\": " + e.getMessage());
        }
    }

    /** 读 stdout 逐行摄入；进程退出时 flush 残留缓冲并标记 dead，由 reconcile 退避重启 */
    private void pump(AppService.AppInfo app, Process p) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                ingestor.pushLine(app, line);
            }
        } catch (Exception e) {
            // 进程已退出 / 流已关闭，忽略
        } finally {
            lastExit.put(app.id, System.currentTimeMillis());
            Running r = running.get(app.id);
            if (r != null) {
                r.dead = true;
            }
            ingestor.flush(app);
        }
    }

    private void stop(long id) {
        Running r = running.remove(id);
        if (r != null && r.process != null) {
            killTree(r.process);
        }
        AppService.AppInfo app = appService.resolveById(id);
        if (app != null) {
            ingestor.flush(app);
        }
    }

    /**
     * 杀掉整棵命令进程树。
     * Windows 上 destroy 只杀顶层 cmd.exe，经 {@code cmd /c python ...} 拉起的孙进程
     * 会变成孤儿、继续持有管道写日志，必须用 {@code taskkill /T /F} 连树一起杀；
     * Linux 上 {@code /bin/sh -c 单命令} 通常会 exec 成命令本身（pid 即命令进程），
     * 用 {@code pkill -P} 补杀管道等直接子进程，再 destroy 主进程。
     * pid 通过反射读取（Java 9+ 的 Process#pid），JDK8 下拿不到就退化为仅杀主进程。
     */
    private static void killTree(Process p) {
        long pid = pidOf(p);
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                if (pid > 0) {
                    Process tk = new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(pid)).start();
                    // 必须等 taskkill 跑完：若先 destroy 主进程，子进程会被过继，
                    // taskkill /T 就枚举不到树，孙进程（如 python）会变孤儿继续写日志
                    tk.waitFor(3, TimeUnit.SECONDS);
                }
            } else if (pid > 0) {
                Process tk = new ProcessBuilder("pkill", "-TERM", "-P", String.valueOf(pid)).start();
                // 同理：子进程必须还活着（PPID 未变）时 pkill 才找得到
                tk.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Exception ignore) {
            // 兜底走 destroyForcibly
        }
        p.destroyForcibly();
    }

    /** 反射读取进程 pid（Java 9+），JDK8 返回 -1 */
    private static long pidOf(Process p) {
        try {
            java.lang.reflect.Method m = Process.class.getMethod("pid");
            Object r = m.invoke(p);
            return (r instanceof Number) ? ((Number) r).longValue() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 按操作系统选择 shell 解释器，从而支持管道/重定向等语法 */
    private static String[] buildCmd(String cmd) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new String[] { "cmd.exe", "/c", cmd };
        }
        return new String[] { "/bin/sh", "-c", cmd };
    }

    /** 运行态：记录执行的命令原文（用于判定「命令是否变更」）与进程句柄 */
    static class Running {
        final String cmd;
        final Process process;
        volatile boolean dead = false;

        Running(String cmd, Process process) {
            this.cmd = cmd;
            this.process = process;
        }
    }
}
