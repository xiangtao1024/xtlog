package top.xtcoder.xtlog.controller;

import java.util.Date;
import java.util.List;
import java.util.Map;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.web.annotation.PostMapping;
import top.xtcoder.xtboot.web.annotation.RequestBody;
import top.xtcoder.xtboot.web.annotation.RestController;
import top.xtcoder.xtboot.web.model.ApiResponse;

import top.xtcoder.xtlog.ingest.IngestQueue;
import top.xtcoder.xtlog.ingest.LogBroadcast;
import top.xtcoder.xtlog.service.AppService;

/**
 * 日志摄入接口：业务方通过 HTTP 把日志 POST 过来。
 * 请求体支持两种形态：
 *   - 批量：{ "token": "...", "logs": [ {...}, {...} ] }
 *   - 单条：{ "token": "...", "level": "INFO", "logger": "x", "message": "y", "timestamp": 123, "traceId": "...", "srcIp": "1.2.3.4" }
 *
 * 编码约定：请求体必须是 UTF-8。HTTP 报文的字符集无法在服务端可靠推断，
 * 若上报端用 GBK 等编码发送，中文字节会被解成替换字符（U+FFFD）且不可逆。
 * 这里对解码结果做一次体检，发现替换字符就在响应里明确告警（不拒绝、不丢数据），
 * 便于上报端第一时间定位问题，而不是事后在界面上看到乱码。
 */
@RestController
public class IngestController {

    /** Unicode 替换字符：字节无法按声明编码解码时的产物 */
    private static final char REPLACEMENT_CHAR = '\uFFFD';

    @Autowired
    private IngestQueue queue;

    @Autowired
    private LogBroadcast broadcast;

    @Autowired
    private AppService appService;

    @PostMapping("/api/logs")
    public Object ingest(@RequestBody Dict body) {
        String token = (String) body.get("token");
        AppService.AppInfo app = appService.resolveByToken(token);
        if (app == null) {
            Dict r = new Dict();
            r.put("error", "invalid token");
            return ApiResponse.ok(r);
        }
        Object logs = body.get("logs");
        int count = 0;
        if (logs instanceof List) {
            for (Object o : (List<?>) logs) {
                if (o instanceof Map) {
                    pushOne(app, (Map<String, Object>) o);
                    count++;
                }
            }
        } else if (body.containsKey("level") || body.containsKey("message")) {
            pushOne(app, body);
            count++;
        } else {
            Dict r = new Dict();
            r.put("error", "empty payload");
            return ApiResponse.ok(r);
        }
        Dict r = new Dict();
        r.put("accepted", count);
        int badChars = countReplacementChars(body);
        if (badChars > 0) {
            r.put("encodingWarning", "检测到 " + badChars + " 个乱码字符(U+FFFD)，请求体不是合法 UTF-8。"
                    + "请确保上报端以 UTF-8 发送（如 Content-Type: application/json;charset=UTF-8，"
                    + "并确认进程默认编码为 UTF-8，例如 JVM 加 -Dfile.encoding=UTF-8）。");
        }
        return ApiResponse.ok(r);
    }

    /** 递归统计请求体里出现的替换字符数量（String / Map / List / 数组） */
    private static int countReplacementChars(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof String) {
            String s = (String) o;
            int n = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == REPLACEMENT_CHAR) {
                    n++;
                }
            }
            return n;
        }
        if (o instanceof Map) {
            int n = 0;
            for (Object v : ((Map<?, ?>) o).values()) {
                n += countReplacementChars(v);
            }
            return n;
        }
        if (o instanceof Iterable) {
            int n = 0;
            for (Object v : (Iterable<?>) o) {
                n += countReplacementChars(v);
            }
            return n;
        }
        return 0;
    }

    private void pushOne(AppService.AppInfo app, Map<String, Object> m) {
        Dict d = new Dict();
        d.put("app_id", app.id);
        d.put("category_id", app.categoryId);
        d.put("level", str(m.get("level"), "INFO"));
        d.put("logger", str(m.get("logger"), ""));
        d.put("thread", str(m.get("thread"), ""));
        d.put("message", str(m.get("message"), ""));
        d.put("trace_id", str(m.get("traceId"), null));
        d.put("src_ip", str(m.get("srcIp"), null));
        Object ts = m.get("timestamp");
        long logTime = (ts instanceof Number) ? ((Number) ts).longValue() : System.currentTimeMillis();
        d.put("log_time", new Date(logTime));
        d.put("receive_time", new Date());
        queue.offer(d);
        broadcast.onLog(d);
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }
}
