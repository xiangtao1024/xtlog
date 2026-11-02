package top.xtcoder.xtlog.ingest;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtlog.service.AppService;
import top.xtcoder.xtlog.service.IngestConfig;
import top.xtcoder.xtlog.util.HostAddresses;

/**
 * 文本日志归一化与摄入：供文件采集器（整块文本）与命令采集器（流式逐行）共用。
 * 负责多行堆栈合并、行首时间戳解析、级别/logger 推断，最后落成 Dict 送进摄入队列。
 */
@Component
public class LogTextIngestor {

    /**
     * 行首时间戳识别：支持多种常见格式，做到「只要是日志就能采集」，而非只认 Java/Logback 格式。
     *   - Java/Logback/ISO8601: 2026-10-10 09:15:32.123  /  2026-10-10T09:15:32+08:00
     *   - Nginx error:          2026/10/10 09:15:32
     *   - Nginx access:         [10/Oct/2026:09:15:32 +0000]（时间戳不在行首，允许在行内）
     *   - Syslog:               Oct 10 09:15:32
     * 无法识别时间戳的格式（如纯应用输出）仍会被采集，只是按续行规则合并、时间记接收时刻。
     */
    private static final Pattern TS_JAVA = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,3}))?(?:Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern TS_NGINX_ERR = Pattern.compile(
            "^(\\d{4})/(\\d{2})/(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})");
    private static final Pattern TS_NGINX_ACC = Pattern.compile(
            "\\[(\\d{2})/([A-Za-z]{3})/(\\d{4}):(\\d{2}):(\\d{2}):(\\d{2})");
    private static final Pattern TS_SYSLOG = Pattern.compile(
            "^([A-Za-z]{3})\\s+(\\d{1,2})\\s+(\\d{2}):(\\d{2}):(\\d{2})");

    private static final java.util.Map<String, Integer> MONTHS = new java.util.HashMap<String, Integer>();
    static {
        String[] m = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        for (int i = 0; i < m.length; i++) MONTHS.put(m[i], i + 1);
    }

    private static final Set<String> LEVEL_WORDS = new HashSet<String>(Arrays.asList(
            "TRACE", "DEBUG", "INFO", "NOTICE", "WARN", "WARNING",
            "ERROR", "ERR", "FATAL", "CRITICAL", "SEVERE"));

    @Autowired
    private IngestQueue queue;
    @Autowired
    private LogBroadcast broadcast;
    @Autowired
    private IngestConfig ingestConfig;

    /** 流式采集（命令采集器）用的按应用续行缓冲：攒到「新条目」再整块 emit */
    private final ConcurrentHashMap<Long, StringBuilder> buffers = new ConcurrentHashMap<Long, StringBuilder>();

    /** 缓冲安全上限：超过则强制 flush，避免源源不断、且不含「新条目」特征的命令把内存撑爆 */
    private static final int MAX_BUFFER_BYTES = 256 * 1024;

    /**
     * 整块文本摄入（文件采集器）：按「新条目」特征切分多行、合并堆栈。
     */
    public void ingestText(AppService.AppInfo app, String text) {
        String[] raw = text.split("\n", -1);
        StringBuilder entry = null;
        for (int i = 0; i < raw.length; i++) {
            String line = stripCr(raw[i]);
            if (isNewEntry(line)) {
                if (entry != null) {
                    emit(app, entry.toString());
                }
                entry = new StringBuilder(line);
            } else {
                if (entry == null) {
                    entry = new StringBuilder(line);
                } else {
                    entry.append("\n").append(line);
                }
            }
        }
        if (entry != null) {
            emit(app, entry.toString());
        }
    }

    /**
     * 流式逐行摄入（命令采集器）：内部维持按应用的续行缓冲，
     * 遇到「新条目」特征才把上一段整块送出，从而正确合并多行堆栈。
     */
    public void pushLine(AppService.AppInfo app, String line) {
        line = stripCr(line);
        StringBuilder buf = buffers.computeIfAbsent(app.id, k -> new StringBuilder());
        if (isNewEntry(line)) {
            if (buf.length() > 0) {
                emit(app, buf.toString());
                buf.setLength(0);
            }
            buf.append(line);
        } else {
            if (buf.length() == 0) {
                buf.append(line);
            } else {
                buf.append("\n").append(line);
            }
        }
        // 安全兜底：缓冲超过上限（多见于不含时间戳/级别特征的纯流式输出）直接 flush
        if (buf.length() > MAX_BUFFER_BYTES) {
            emit(app, buf.toString());
            buf.setLength(0);
        }
    }

    /** 进程退出/停止时刷掉残留缓冲，避免最后一条多行日志丢尾 */
    public void flush(AppService.AppInfo app) {
        StringBuilder buf = buffers.remove(app.id);
        if (buf != null && buf.length() > 0) {
            emit(app, buf.toString());
        }
    }

    private void emit(AppService.AppInfo app, String text) {
        Dict d = new Dict();
        d.put("app_id", app.id);
        d.put("category_id", app.categoryId);
        d.put("level", guessLevel(text));
        d.put("logger", guessLogger(text));
        d.put("message", text);
        d.put("trace_id", null);
        // 来源 IP：文件/命令采集的日志物理上就落在本机，打上本机 IP，
        // 便于在微服务/多机场景下区分「这条日志来自哪台机器」。
        // HTTP 采集不走这里，由上报端通过 srcIp 字段自己带过来（见 IngestController）。
        String srcIp = null;
        String st = app.sourceType;
        if ("FILE".equalsIgnoreCase(st) || "CMD".equalsIgnoreCase(st)) {
            srcIp = HostAddresses.localIp();
        }
        d.put("src_ip", srcIp);
        // 优先采用日志行自带的时间戳，解析不到才用采集时刻，避免历史日志时间轴错乱
        Date logTime = parseLogTime(text);
        d.put("log_time", logTime != null ? logTime : new Date());
        d.put("receive_time", new Date());
        // 与 HTTP 通道一致：超长日志拆成多条连续记录，不丢数据、不截断尾部。
        for (Dict seg : LogSplitter.expand(d, ingestConfig.maxChars())) {
            queue.offer(seg);
            broadcast.onLog(seg);
        }
    }

    /** 解析首行时间戳，支持多种常见日志格式；全部失败返回 null（由调用方回退到接收时刻） */
    private Date parseLogTime(String text) {
        String first = firstLine(text);
        Date d;
        if ((d = tryJava(first)) != null) return d;
        if ((d = tryNginxError(first)) != null) return d;
        if ((d = tryNginxAccess(first)) != null) return d;
        if ((d = trySyslog(first)) != null) return d;
        return null;
    }

    private static Date toDate(int y, int mo, int d, int h, int mi, int s, int ms) {
        try {
            java.time.LocalDateTime ldt = java.time.LocalDateTime.of(y, mo, d, h, mi, s, ms * 1_000_000);
            return Date.from(ldt.atZone(java.time.ZoneId.systemDefault()).toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    private Date tryJava(String s) {
        Matcher m = TS_JAVA.matcher(s);
        if (!m.find() || m.start() != 0) return null;
        try {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            int h = Integer.parseInt(m.group(4));
            int mi = Integer.parseInt(m.group(5));
            int sec = Integer.parseInt(m.group(6));
            int ms = 0;
            String msStr = m.group(7);
            if (msStr != null) {
                ms = Integer.parseInt(msStr) * (int) Math.pow(10, 3 - msStr.length());
            }
            return toDate(y, mo, d, h, mi, sec, ms);
        } catch (Exception e) {
            return null;
        }
    }

    private Date tryNginxError(String s) {
        Matcher m = TS_NGINX_ERR.matcher(s);
        if (!m.find() || m.start() != 0) return null;
        try {
            return toDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), 0);
        } catch (Exception e) {
            return null;
        }
    }

    private Date tryNginxAccess(String s) {
        Matcher m = TS_NGINX_ACC.matcher(s);
        if (!m.find()) return null; // nginx access 时间戳在行内（IP 之后），允许任意位置
        try {
            Integer mo = MONTHS.get(m.group(2));
            if (mo == null) return null;
            return toDate(Integer.parseInt(m.group(3)), mo, Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6)), 0);
        } catch (Exception e) {
            return null;
        }
    }

    private Date trySyslog(String s) {
        Matcher m = TS_SYSLOG.matcher(s);
        if (!m.find() || m.start() != 0) return null;
        try {
            Integer mo = MONTHS.get(m.group(1));
            if (mo == null) return null;
            int y = java.time.Year.now().getValue();
            return toDate(y, mo, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), 0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 跳过首行时间戳，返回其后文本起始位置（用于 logger/线程推断） */
    private int tsEnd(String first) {
        Matcher a = TS_JAVA.matcher(first);
        if (a.find() && a.start() == 0) return a.end();
        Matcher n = TS_NGINX_ERR.matcher(first);
        if (n.find() && n.start() == 0) return n.end();
        Matcher x = TS_NGINX_ACC.matcher(first);
        if (x.find()) return x.end();
        Matcher s = TS_SYSLOG.matcher(first);
        if (s.find() && s.start() == 0) return s.end();
        return 0;
    }

    private String guessLevel(String text) {
        String first = firstLine(text);
        String[] parts = first.split("\\s+");
        for (int i = 0; i < parts.length && i < 8; i++) {
            String u = stripBrackets(parts[i]);
            if (LEVEL_WORDS.contains(u)) {
                return normalizeLevel(u);
            }
        }
        String up = first.toUpperCase();
        if (up.contains("FATAL")) {
            return "ERROR";
        }
        if (up.contains("ERROR")) {
            return "ERROR";
        }
        if (up.contains("WARN")) {
            return "WARN";
        }
        if (up.contains("DEBUG")) {
            return "DEBUG";
        }
        if (up.contains("TRACE")) {
            return "TRACE";
        }
        return "INFO";
    }

    private static String normalizeLevel(String word) {
        String u = word.toUpperCase();
        if ("FATAL".equals(u) || "SEVERE".equals(u)) {
            return "ERROR";
        }
        return u;
    }

    /**
     * 从首行推断 logger：跳过行首时间戳与级别词，优先取形如 com.demo.Foo 的类名，
     * 否则取第一个有效 token。避免把时间戳误当 logger（如 "2026-09-23"）。
     */
    private String guessLogger(String text) {
        String first = firstLine(text);
        int end = tsEnd(first);
        String rest = end > 0 ? first.substring(end).trim() : first;
        String[] parts = rest.split("\\s+");
        String fallback = null;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() == 0) {
                continue;
            }
            String u = stripBrackets(p);
            if (LEVEL_WORDS.contains(u)) {
                continue;
            }
            if (fallback == null) {
                fallback = p;
            }
            if (p.indexOf('.') > 0) {
                return truncate(p, 200);
            }
        }
        return fallback == null ? "" : truncate(fallback, 200);
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl >= 0 ? text.substring(0, nl) : text;
    }

    private static String stripBrackets(String token) {
        int s = 0;
        int e = token.length();
        while (s < e && (token.charAt(s) == '[' || token.charAt(s) == '(')) {
            s++;
        }
        while (e > s && (token.charAt(e - 1) == ']' || token.charAt(e - 1) == ')' || token.charAt(e - 1) == ':')) {
            e--;
        }
        return token.substring(s, e).toUpperCase();
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 去掉行尾 CR（Windows CRLF 文件每行尾部都带 \r） */
    private static String stripCr(String line) {
        if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    /** 是否为一条新日志的起始行（行首/行内有可识别的时间戳，或首词是级别词） */
    boolean isNewEntry(String line) {
        if (line == null || line.length() == 0) {
            return false;
        }
        if (hasTimestamp(line)) {
            return true;
        }
        int sp = line.indexOf(' ');
        String head = (sp > 0 ? line.substring(0, sp) : line).toUpperCase();
        return LEVEL_WORDS.contains(head);
    }

    /** 行内是否含有可识别的时间戳（nginx access 允许时间戳在行内，其余要求从行首开始） */
    private boolean hasTimestamp(String line) {
        if (TS_NGINX_ACC.matcher(line).find()) {
            return true;
        }
        Matcher a = TS_JAVA.matcher(line);
        if (a.find() && a.start() == 0) return true;
        Matcher b = TS_NGINX_ERR.matcher(line);
        if (b.find() && b.start() == 0) return true;
        Matcher c = TS_SYSLOG.matcher(line);
        if (c.find() && c.start() == 0) return true;
        return false;
    }
}
