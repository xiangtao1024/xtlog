package top.xtcoder.xtlog.ingest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;

import top.xtcoder.xtlog.config.DataSourceConfig;

/**
 * 告警多渠道通知器：一条规则可配多个 Webhook + 多个邮箱，触发时逐个投递。
 *
 * <p>channels JSON 格式（向后兼容旧的 {@code {"webhook":"url"}}）：</p>
 * <pre>
 * { "webhooks": ["https://a/hook", "https://b/hook"],
 *   "emails":   ["ops@example.com", "dev@example.com"] }
 * </pre>
 *
 * <p>SMTP 配置从 {@code xt_config} 读取（分字段存，绕开 v VARCHAR(200) 限制）：
 * {@code alert.smtp.host/port/ssl/user/pass/from/fromName}。邮件为纯 JDK 手写 SMTP 客户端，
 * 支持 25/465/587、SSL/STARTTLS、AUTH LOGIN/PLAIN、UTF-8 MIME 编码，不引入第三方依赖。</p>
 *
 * <p>所有异常都只记日志不抛出：通知失败不能反噬告警事件落库与主采集链路。</p>
 */
@Component
public class AlertNotifier {

    @Autowired
    private DbClientHolder holder;

    // ==================== SMTP 配置 key ====================

    public static final String K_SMTP_HOST = "alert.smtp.host";
    public static final String K_SMTP_PORT = "alert.smtp.port";
    public static final String K_SMTP_SSL = "alert.smtp.ssl";       // true = 465 隐式 SSL
    public static final String K_SMTP_STARTTLS = "alert.smtp.starttls"; // true = 587 STARTTLS
    public static final String K_SMTP_USER = "alert.smtp.user";
    public static final String K_SMTP_PASS = "alert.smtp.pass";
    public static final String K_SMTP_FROM = "alert.smtp.from";
    public static final String K_SMTP_FROMNAME = "alert.smtp.fromName";

    /**  SMTP 配置读模型 */
    public static final class SmtpConfig {
        public String host = "";
        public int port = 25;
        public boolean ssl = false;
        public boolean starttls = false;
        public String user = "";
        public String pass = "";
        public String from = "";
        public String fromName = "";

        public boolean configured() {
            return host != null && host.trim().length() > 0;
        }
    }

    /** 从 xt_config 读 SMTP 配置 */
    public SmtpConfig loadSmtpConfig() {
        SmtpConfig c = new SmtpConfig();
        c.host = nvl(DataSourceConfig.getConfig(holder, K_SMTP_HOST));
        c.port = intOr(nvl(DataSourceConfig.getConfig(holder, K_SMTP_PORT)), 25);
        c.ssl = Boolean.parseBoolean(nvl(DataSourceConfig.getConfig(holder, K_SMTP_SSL)));
        c.starttls = Boolean.parseBoolean(nvl(DataSourceConfig.getConfig(holder, K_SMTP_STARTTLS)));
        c.user = nvl(DataSourceConfig.getConfig(holder, K_SMTP_USER));
        c.pass = nvl(DataSourceConfig.getConfig(holder, K_SMTP_PASS));
        c.from = nvl(DataSourceConfig.getConfig(holder, K_SMTP_FROM));
        if (c.from.length() == 0) {
            c.from = c.user;
        }
        c.fromName = nvl(DataSourceConfig.getConfig(holder, K_SMTP_FROMNAME));
        if (c.fromName.length() == 0) {
            c.fromName = "xtlog 告警";
        }
        return c;
    }

    /** 保存 SMTP 配置（pass 为空字符串时保留旧值，便于「不回显密码」的编辑体验） */
    public void saveSmtpConfig(Dict body) {
        put(K_SMTP_HOST, strOr(body.get("host"), ""));
        put(K_SMTP_PORT, String.valueOf(intOr(body.get("port"), 25)));
        put(K_SMTP_SSL, String.valueOf(boolOr(body.get("ssl"), false)));
        put(K_SMTP_STARTTLS, String.valueOf(boolOr(body.get("starttls"), false)));
        put(K_SMTP_USER, strOr(body.get("user"), ""));
        String pass = strOr(body.get("pass"), "");
        if (pass.length() > 0) {
            put(K_SMTP_PASS, pass);
        }
        put(K_SMTP_FROM, strOr(body.get("from"), ""));
        put(K_SMTP_FROMNAME, strOr(body.get("fromName"), ""));
    }

    private void put(String key, String value) {
        DataSourceConfig.setConfig(holder, key, value);
    }

    /** 给前端回显的 SMTP 配置（密码只回显是否已设置，绝不回显明文） */
    public Dict smtpConfigForFrontend() {
        SmtpConfig c = loadSmtpConfig();
        Dict d = new Dict();
        d.put("host", c.host);
        d.put("port", Integer.valueOf(c.port));
        d.put("ssl", Boolean.valueOf(c.ssl));
        d.put("starttls", Boolean.valueOf(c.starttls));
        d.put("user", c.user);
        d.put("passSet", Boolean.valueOf(c.pass.length() > 0));
        d.put("from", c.from);
        d.put("fromName", c.fromName);
        return d;
    }

    // ==================== 渠道解析 ====================

    /** 解析出的通知渠道列表 */
    public static final class Channels {
        public final List<String> webhooks = new ArrayList<String>();
        public final List<String> emails = new ArrayList<String>();

        public boolean isEmpty() {
            return webhooks.isEmpty() && emails.isEmpty();
        }
    }

    /**
     * 解析 channels JSON。兼容三种历史/新格式：
     * <ul>
     *   <li>{@code {"webhook":"url"}} —— 旧单 webhook</li>
     *   <li>{@code {"webhooks":["u1","u2"],"emails":["e1","e2"]}} —— 新多渠道</li>
     *   <li>裸 URL 字符串 —— 更早期的容错</li>
     * </ul>
     */
    public static Channels parseChannels(String channels) {
        Channels out = new Channels();
        if (channels == null || channels.trim().length() == 0) {
            return out;
        }
        String s = channels.trim();
        if (!s.startsWith("{")) {
            // 裸 URL 兼容
            if (s.startsWith("http://") || s.startsWith("https://")) {
                out.webhooks.add(s);
            }
            return out;
        }
        collectStringValues(s, "webhook", out.webhooks);
        collectStringValues(s, "webhooks", out.webhooks);
        collectStringValues(s, "email", out.emails);
        collectStringValues(s, "emails", out.emails);
        return out;
    }

    /** 从 JSON 中提取某个 key 的字符串值：支持 "k":"v" 与 "k":["v1","v2"] */
    private static void collectStringValues(String json, String key, List<String> out) {
        // 先试数组形式 "key": [ ... ]
        Pattern arr = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]",
                Pattern.CASE_INSENSITIVE);
        Matcher am = arr.matcher(json);
        if (am.find()) {
            Matcher sm = Pattern.compile("\"([^\"]+)\"").matcher(am.group(1));
            while (sm.find()) {
                addUnique(out, sm.group(1));
            }
            return;
        }
        // 再试单值形式 "key": "..."
        Pattern single = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE);
        Matcher m = single.matcher(json);
        if (m.find()) {
            addUnique(out, m.group(1));
        }
    }

    private static void addUnique(List<String> list, String v) {
        if (v != null && v.trim().length() > 0 && !list.contains(v.trim())) {
            list.add(v.trim());
        }
    }

    // ==================== 统一投递入口 ====================

    /**
     * 向规则配置的所有渠道投递告警。同步执行（调用方已在异步线程中）。
     *
     * @return 投递结果摘要，形如 "webhook 2/2, email 1/2"（成功数/总数）
     */
    public String notifyAll(String channels, Dict rule, AlertEngine.MatchResult mr,
                            long eventId, String appName) {
        Channels ch = parseChannels(channels);
        if (ch.isEmpty()) {
            return "no channels";
        }
        String payload = buildWebhookPayload(eventId, rule, mr, appName);
        String subject = "【xtlog 告警】" + String.valueOf(rule.get("name"))
                + " - " + appName + " - " + (mr.sampleLevel == null ? "" : mr.sampleLevel);
        String html = buildMailHtml(eventId, rule, mr, appName);

        int okWh = 0;
        for (String url : ch.webhooks) {
            if (postWebhook(url, payload)) {
                okWh++;
            }
        }
        int okMail = 0;
        if (!ch.emails.isEmpty()) {
            SmtpConfig smtp = loadSmtpConfig();
            if (!smtp.configured()) {
                System.err.println("[xtlog] alert email skipped: smtp not configured ("
                        + ch.emails.size() + " recipients)");
            } else {
                for (String to : ch.emails) {
                    if (sendMail(smtp, to, subject, html)) {
                        okMail++;
                    }
                }
            }
        }
        return "webhook " + okWh + "/" + ch.webhooks.size()
                + ", email " + okMail + "/" + ch.emails.size();
    }

    /** 发送一封测试邮件，返回错误信息（成功返回 null） */
    public String sendTestMail(String to) {
        SmtpConfig smtp = loadSmtpConfig();
        if (!smtp.configured()) {
            return "SMTP 未配置";
        }
        String subject = "【xtlog】邮件通知测试";
        String html = "<div style='font-family:sans-serif'>"
                + "<h3>xtlog 告警邮件测试</h3>"
                + "<p>如果你收到这封邮件，说明 SMTP 配置正确，告警邮件可以正常送达。</p>"
                + "<p style='color:#888;font-size:12px'>发送时间：" + new java.util.Date() + "</p>"
                + "</div>";
        return sendMailDetailed(smtp, to, subject, html);
    }

    // ==================== Webhook ====================

    private static boolean postWebhook(String url, String payload) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload.getBytes(Charset.forName("UTF-8")));
            } finally {
                os.close();
            }
            int code = conn.getResponseCode();
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
            try {
                while (r.read() != -1) {
                    // drain
                }
            } finally {
                r.close();
            }
            if (code >= 400) {
                System.err.println("[xtlog] webhook " + url + " returned " + code);
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("[xtlog] webhook " + url + " failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ==================== SMTP 客户端（纯 JDK） ====================

    private static boolean sendMail(SmtpConfig c, String to, String subject, String html) {
        String err = sendMailDetailed(c, to, subject, html);
        if (err != null) {
            System.err.println("[xtlog] alert mail to " + to + " failed: " + err);
        }
        return err == null;
    }

    /** 返回 null 表示成功，否则返回错误描述 */
    private static String sendMailDetailed(SmtpConfig c, String to, String subject, String html) {
        Socket socket = null;
        BufferedReader in = null;
        BufferedWriter out = null;
        try {
            socket = connect(c);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

            String line = readReply(in);
            if (!startsWith(line, 220)) {
                return "greeting: " + line;
            }

            line = sendCmd(in, out, "EHLO xtlog-alert");
            if (c.starttls && !c.ssl) {
                if (!startsWith(line, 250)) {
                    return "EHLO before STARTTLS: " + line;
                }
                line = sendCmd(in, out, "STARTTLS");
                if (!startsWith(line, 220)) {
                    return "STARTTLS: " + line;
                }
                SSLSocket sslSock = wrapTls(socket, c.host, c.port);
                socket = sslSock;
                in = new BufferedReader(new InputStreamReader(sslSock.getInputStream(), "UTF-8"));
                out = new BufferedWriter(new OutputStreamWriter(sslSock.getOutputStream(), "UTF-8"));
                line = sendCmd(in, out, "EHLO xtlog-alert");
            }
            if (!startsWith(line, 250)) {
                return "EHLO: " + line;
            }

            if (c.user != null && c.user.trim().length() > 0) {
                line = sendCmd(in, out, "AUTH LOGIN");
                if (!startsWith(line, 334)) {
                    // 退回 PLAIN
                    String token = b64("\0" + c.user + "\0" + c.pass);
                    line = sendCmd(in, out, "AUTH PLAIN " + token);
                    if (!startsWith(line, 235)) {
                        return "AUTH PLAIN: " + line;
                    }
                } else {
                    line = sendCmd(in, out, b64(c.user));
                    if (!startsWith(line, 334)) {
                        return "AUTH user: " + line;
                    }
                    line = sendCmd(in, out, b64(c.pass));
                    if (!startsWith(line, 235)) {
                        return "AUTH pass: " + line;
                    }
                }
            }

            line = sendCmd(in, out, "MAIL FROM:<" + c.from + ">");
            if (!startsWith(line, 250)) {
                return "MAIL FROM: " + line;
            }
            line = sendCmd(in, out, "RCPT TO:<" + to + ">");
            if (!startsWith(line, 250) && !startsWith(line, 251)) {
                return "RCPT TO: " + line;
            }
            line = sendCmd(in, out, "DATA");
            if (!startsWith(line, 354)) {
                return "DATA: " + line;
            }

            writeMime(out, c, to, subject, html);
            line = readReply(in);
            if (!startsWith(line, 250)) {
                return "message body: " + line;
            }
            sendCmd(in, out, "QUIT");
            return null;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            closeQuietly(socket);
        }
    }

    private static Socket connect(SmtpConfig c) throws Exception {
        if (c.ssl) {
            SSLSocketFactory factory = trustAllFactory();
            SSLSocket s = (SSLSocket) factory.createSocket(c.host, c.port);
            s.startHandshake();
            return s;
        }
        return new Socket(c.host, c.port);
    }

    private static SSLSocket wrapTls(Socket plain, String host, int port) throws Exception {
        SSLSocketFactory factory = trustAllFactory();
        SSLSocket s = (SSLSocket) factory.createSocket(plain, host, port, true);
        s.startHandshake();
        return s;
    }

    /** 容忍自签名证书（内网邮件网关常见）；只用于告警出站，不影响主链路 */
    private static SSLSocketFactory trustAllFactory() throws Exception {
        TrustManager[] tm = new TrustManager[] { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        } };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new SecureRandom());
        return ctx.getSocketFactory();
    }

    private static void writeMime(BufferedWriter w, SmtpConfig c, String to,
                                  String subject, String html) throws Exception {
        w.write("From: " + mimeHeader(c.fromName) + " <" + c.from + ">\r\n");
        w.write("To: " + to + "\r\n");
        w.write("Subject: " + mimeHeader(subject) + "\r\n");
        w.write("MIME-Version: 1.0\r\n");
        w.write("Content-Type: text/html; charset=UTF-8\r\n");
        w.write("Content-Transfer-Encoding: base64\r\n");
        w.write("\r\n");
        String body = b64(html);
        // 按 76 字符折行（RFC 2045）
        for (int i = 0; i < body.length(); i += 76) {
            w.write(body.substring(i, Math.min(i + 76, body.length())) + "\r\n");
        }
        w.write(".\r\n");
        w.flush();
    }

    private static String mimeHeader(String s) {
        return "=?UTF-8?B?" + b64(s == null ? "" : s) + "?=";
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(Charset.forName("UTF-8")));
    }

    /** 读一行 SMTP 应答；多行应答（250-xxx）只读最后一行 */
    private static String readReply(BufferedReader in) throws Exception {
        String line = null;
        String last = null;
        while ((line = in.readLine()) != null) {
            last = line;
            if (line.length() < 4 || line.charAt(3) == ' ') {
                break;
            }
        }
        return last == null ? "" : last;
    }

    private static String sendCmd(BufferedReader in, BufferedWriter w, String cmd) throws Exception {
        w.write(cmd + "\r\n");
        w.flush();
        return readReply(in);
    }

    private static boolean startsWith(String line, int code) {
        return line != null && line.startsWith(String.valueOf(code));
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignore) { }
        }
    }

    // ==================== 小工具 ====================

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String strOr(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.length() == 0 ? def : s;
    }

    private static int intOr(Object v, int def) {
        if (v == null) return def;
        try {
            return v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean boolOr(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        String s = String.valueOf(v);
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    // ==================== 消息体构建 ====================

    /** 手写 JSON（不依赖 JsonUtils），字段语义与原 AlertEngine 的 webhook payload 保持一致 */
    private static String buildWebhookPayload(long eventId, Dict rule, AlertEngine.MatchResult mr,
                                              String appName) {
        int threshold = toInt(rule.get("threshold"), 1);
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"event_id\":").append(eventId).append(",");
        sb.append("\"rule_id\":").append(toLong(rule.get("id"))).append(",");
        sb.append("\"rule_name\":").append(jq(String.valueOf(rule.get("name")))).append(",");
        sb.append("\"app_id\":").append(toLong(rule.get("app_id"))).append(",");
        sb.append("\"app_name\":").append(jq(appName)).append(",");
        sb.append("\"level\":").append(jq(mr.sampleLevel == null ? "" : mr.sampleLevel)).append(",");
        sb.append("\"hit_count\":").append(mr.hitCount).append(",");
        sb.append("\"threshold\":").append(threshold).append(",");
        sb.append("\"window_sec\":").append(toInt(rule.get("window_sec"), 300)).append(",");
        sb.append("\"first_time\":").append(System.currentTimeMillis()).append(",");
        sb.append("\"summary\":").append(jq(summary(mr, threshold))).append(",");
        sb.append("\"sample_message\":").append(jq(truncate(mr.sampleMessage, 1000)));
        sb.append("}");
        return sb.toString();
    }

    private static String buildMailHtml(long eventId, Dict rule, AlertEngine.MatchResult mr,
                                        String appName) {
        int threshold = toInt(rule.get("threshold"), 1);
        String level = mr.sampleLevel == null ? "" : mr.sampleLevel;
        String color = "ERROR".equalsIgnoreCase(level) ? "#d93025"
                : "WARN".equalsIgnoreCase(level) ? "#f9a825" : "#1a73e8";
        StringBuilder sb = new StringBuilder();
        sb.append("<div style='font-family:-apple-system,Segoe UI,sans-serif;max-width:720px'>");
        sb.append("<h2 style='color:").append(color).append(";margin:0 0 8px'>xtlog 告警触发</h2>");
        sb.append("<table style='border-collapse:collapse;width:100%;font-size:14px'>");
        row(sb, "规则", String.valueOf(rule.get("name")));
        row(sb, "应用", appName);
        row(sb, "级别", level);
        row(sb, "命中", mr.hitCount + " 条（阈值 " + threshold + "）");
        row(sb, "事件 ID", String.valueOf(eventId));
        sb.append("</table>");
        sb.append("<p style='margin:16px 0 6px;font-weight:600'>摘要</p>");
        sb.append("<div style='background:#f5f5f5;padding:10px;border-radius:6px;font-size:13px'>")
                .append(escapeHtml(summary(mr, threshold))).append("</div>");
        if (mr.sampleMessage != null && mr.sampleMessage.length() > 0) {
            sb.append("<p style='margin:16px 0 6px;font-weight:600'>日志样例</p>");
            sb.append("<pre style='background:#f5f5f5;padding:10px;border-radius:6px;font-size:12px;")
                    .append("white-space:pre-wrap;word-break:break-all;max-height:300px;overflow:auto'>")
                    .append(escapeHtml(truncate(mr.sampleMessage, 2000))).append("</pre>");
        }
        sb.append("<p style='color:#999;font-size:12px;margin-top:20px'>由 xtlog-server 自动发出 · ")
                .append(new java.util.Date()).append("</p>");
        sb.append("</div>");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("<tr><td style='padding:6px 10px;color:#888;width:80px;border-bottom:1px solid #eee'>")
                .append(escapeHtml(k)).append("</td>");
        sb.append("<td style='padding:6px 10px;border-bottom:1px solid #eee'>")
                .append(escapeHtml(v)).append("</td></tr>");
    }

    private static String summary(AlertEngine.MatchResult mr, int threshold) {
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(mr.hitCount).append(" 条（阈值 ").append(threshold).append("）");
        if (mr.sampleLevel != null && mr.sampleLevel.length() > 0) {
            sb.append("，最高级别 ").append(mr.sampleLevel);
        }
        if (mr.sampleMessage != null && mr.sampleMessage.length() > 0) {
            sb.append("，样例：").append(truncate(mr.sampleMessage, 180));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** JSON 字符串转义（对齐 AlertEngine.q） */
    private static String jq(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private static long toLong(Object o) {
        return DataSourceConfig.tsOf(o, 0L);
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); }
        catch (Exception e) { return def; }
    }
}
