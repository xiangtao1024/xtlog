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
import top.xtcoder.xtlog.util.HostAddresses;

/**
 * 文本日志归一化与摄入：供文件采集器（整块文本）与命令采集器（流式逐行）共用。
 * 负责多行堆栈合并、行首时间戳解析、级别/logger 推断，最后落成 Dict 送进摄入队列。
 */
@Component
public class LogTextIngestor {

    /** 行首时间戳：yyyy-MM-dd HH:mm:ss[.SSS]，兼容逗号分隔毫秒（log4j2 风格） */
    private static final Pattern TS_PATTERN = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,3}))?");

    private static final Set<String> LEVEL_WORDS = new HashSet<String>(Arrays.asList(
            "TRACE", "DEBUG", "INFO", "NOTICE", "WARN", "WARNING",
            "ERROR", "ERR", "FATAL", "CRITICAL", "SEVERE"));

    @Autowired
    private IngestQueue queue;
    @Autowired
    private LogBroadcast broadcast;

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
        queue.offer(d);
        broadcast.onLog(d);
    }

    /** 解析首行行首时间戳 yyyy-MM-dd HH:mm:ss[.SSS]，失败返回 null */
    private Date parseLogTime(String text) {
        String first = firstLine(text);
        Matcher m = TS_PATTERN.matcher(first);
        if (!m.find()) {
            return null;
        }
        String millis = m.group(7);
        String pattern = millis == null ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm:ss.SSS";
        String value = m.group(1) + "-" + m.group(2) + "-" + m.group(3) + " "
                + m.group(4) + ":" + m.group(5) + ":" + m.group(6);
        if (millis != null) {
            value = value + "." + padMillis(millis);
        }
        try {
            return new SimpleDateFormat(pattern).parse(value);
        } catch (ParseException e) {
            return null;
        }
    }

    private static String padMillis(String ms) {
        if (ms.length() >= 3) {
            return ms.substring(0, 3);
        }
        StringBuilder sb = new StringBuilder(ms);
        while (sb.length() < 3) {
            sb.append('0');
        }
        return sb.toString();
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
        Matcher m = TS_PATTERN.matcher(first);
        String rest = m.find() ? first.substring(m.end()).trim() : first;
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

    /** 是否为一条新日志的起始行（行首时间戳 或 首词是级别词） */
    boolean isNewEntry(String line) {
        if (line == null || line.length() == 0) {
            return false;
        }
        if (TS_PATTERN.matcher(line).find()) {
            return true;
        }
        int sp = line.indexOf(' ');
        String head = (sp > 0 ? line.substring(0, sp) : line).toUpperCase();
        return LEVEL_WORDS.contains(head);
    }
}
