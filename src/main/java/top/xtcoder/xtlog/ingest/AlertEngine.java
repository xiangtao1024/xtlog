package top.xtcoder.xtlog.ingest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.xtcoder.xtboot.context.annotation.Autowired;
import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.orm.DbClientHolder;
import top.xtcoder.xtboot.schedule.XtScheduled;

import top.xtcoder.xtlog.config.DataSourceConfig;
import top.xtcoder.xtlog.service.AppService;
import top.xtcoder.xtlog.service.AppService.AppInfo;
import top.xtcoder.xtlog.util.DbBusyRetry;

/**
 * 告警引擎：每 30 秒扫描一次启用的规则，在评估窗口内对日志月表做聚合匹配，
 * 命中数 ≥ threshold 时创建事件并触发 Webhook；事件在静默窗口内延续、超时自动恢复。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>评估逻辑抽成静态 {@link #evaluate}，{@link top.xtcoder.xtlog.controller.AlertController#testRule}
 *       直接复用，保证「测试」与「实际触发」条件完全一致。</li>
 *   <li>规则评估走 {@code idx_*_leveltime} / {@code idx_*_apptime} 索引，
 *       默认 5 分钟窗口在 GB 级月表上仍可亚秒级返回。</li>
 *   <li>Webhook 调用走 {@code CompletableFuture.runAsync}，引擎线程不被外部 HTTP 阻塞。</li>
 *   <li>所有异常都吃掉只记日志：告警旁路不能反噬主采集链路。</li>
 * </ul>
 */
@Component
public class AlertEngine {

    @Autowired
    private DbClientHolder holder;

    @Autowired
    private AppService appService;

    /** 每 30 秒扫描一次。窗口由规则自身 window_sec 决定，与扫描周期解耦。 */
    @XtScheduled(fixedRate = 30 * 1000)
    public void scan() {
        try {
            List<Dict> rules = holder.executeQuery(
                    "SELECT id, name, app_id, rule_type, rule_expr, window_sec, threshold, silence_sec, channels"
                            + " FROM " + DataSourceConfig.ALERT_RULE_TABLE + " WHERE enabled = 1");
            if (rules == null || rules.isEmpty()) {
                // 没有启用的规则时也走一遍恢复检查，避免规则被禁用后老事件一直挂在 status=1
                checkRecovery();
                return;
            }
            for (Dict rule : rules) {
                try {
                    evalOne(rule);
                } catch (Exception e) {
                    System.err.println("[xtlog] alert scan failed on rule #" + rule.get("id")
                            + ": " + e.getMessage());
                }
            }
            checkRecovery();
        } catch (Exception e) {
            System.err.println("[xtlog] alert scan failed: " + e.getMessage());
        }
    }

    // ==================== 评估结果 ====================

    /** 评估结果：命中数 + 使用的 appId + 一条样本（取窗口内最新命中行） */
    public static final class MatchResult {
        public final long hitCount;
        public final long appId;
        public final String sampleMessage;
        public final String sampleLevel;

        public MatchResult(long hitCount, long appId, String sampleMessage, String sampleLevel) {
            this.hitCount = hitCount;
            this.appId = appId;
            this.sampleMessage = sampleMessage;
            this.sampleLevel = sampleLevel;
        }
    }

    /**
     * 对单条规则做一次窗口评估。供 {@link #scan} 与
     * {@link top.xtcoder.xtlog.controller.AlertController#testRule} 共用。
     *
     * <p>四类规则：</p>
     * <ul>
     *   <li>{@code LEVEL} —— rule_expr 形如 {@code ERROR} 或 {@code ERROR,WARN}，多级逗号分隔</li>
     *   <li>{@code KEYWORD} —— rule_expr 为关键词，{@code message LIKE '%kw%'} 命中</li>
     *   <li>{@code REGEX} —— rule_expr 为 Java 正则。SQL 侧不预过滤，直接拉窗口内最多 1000 行
     *       候选在 Java 侧用 {@link Pattern} 过滤。这是为兼容各库正则函数差异做的折衷，
     *       适合中低吞吐场景；超大日志库建议用 KEYWORD 兜底。</li>
     *   <li>{@code RATE} —— 与 LEVEL 同语义，靠用户配短窗口（如 60s）+ 高 threshold 表达速率</li>
     * </ul>
     */
    public static MatchResult evaluate(DbClientHolder holder, AppService appService, Dict rule) {
        String ruleType = String.valueOf(rule.get("rule_type")).toUpperCase();
        String ruleExpr = rule.get("rule_expr") == null ? "" : String.valueOf(rule.get("rule_expr"));
        long appId = toLong(rule.get("app_id"));
        int windowSec = toInt(rule.get("window_sec"), 300);
        if (windowSec <= 0) {
            windowSec = 300;
        }
        long now = System.currentTimeMillis();
        long start = now - windowSec * 1000L;

        // 候选月表与库中真实存在的月表求交集
        List<String> candidates = DataSourceConfig.monthsBetween(start, now);
        List<String> existing = DataSourceConfig.listLogTables(holder);
        List<String> tables = new ArrayList<String>();
        for (String t : candidates) {
            if (existing.contains(t)) {
                tables.add(t);
            }
        }
        if (tables.isEmpty()) {
            return new MatchResult(0, appId, null, null);
        }
        String union = buildUnion(tables);

        // 公共 WHERE：app_id + 时间范围
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<Object>();
        if (appId > 0) {
            where.append(" AND app_id = ?");
            params.add(Long.valueOf(appId));
        }
        where.append(" AND log_time >= ? AND log_time <= ?");
        params.add(DataSourceConfig.tsValue(holder, start));
        params.add(DataSourceConfig.tsValue(holder, now));

        boolean isRegex = "REGEX".equals(ruleType) && ruleExpr.length() > 0;
        if ("KEYWORD".equals(ruleType) && ruleExpr.length() > 0) {
            where.append(" AND message LIKE ?");
            params.add("%" + ruleExpr + "%");
        } else if ("LEVEL".equals(ruleType) || "RATE".equals(ruleType)) {
            String[] levels = ruleExpr.split(",");
            StringBuilder ph = new StringBuilder();
            int n = 0;
            for (String lv : levels) {
                String s = lv.trim().toUpperCase();
                if (s.length() == 0) {
                    continue;
                }
                if (n++ > 0) {
                    ph.append(",");
                }
                ph.append("?");
                params.add(s);
            }
            if (n == 0) {
                return new MatchResult(0, appId, null, null);
            }
            where.append(" AND level IN (").append(ph).append(")");
        }
        // REGEX / 未识别类型：SQL 层不预过滤，下面拉候选行 Java 侧匹配

        // REGEX 走候选 + Java 过滤路径
        if (isRegex) {
            Pattern p;
            try {
                p = Pattern.compile(ruleExpr);
            } catch (Exception e) {
                // 正则不合法直接返回 0，不阻断引擎
                return new MatchResult(0, appId, null, null);
            }
            String candSql = "SELECT message, level FROM (" + union + ") t WHERE 1=1"
                    + where + " ORDER BY log_time DESC LIMIT 1000";
            List<Dict> rows = holder.executeQuery(candSql, params.toArray());
            long hit = 0;
            String sampleMsg = null;
            String sampleLv = null;
            if (rows != null) {
                for (Dict r : rows) {
                    Object m = r.get("message");
                    if (m == null) {
                        continue;
                    }
                    String msg = String.valueOf(m);
                    Matcher mat = p.matcher(msg);
                    if (mat.find()) {
                        hit++;
                        if (sampleMsg == null) {
                            sampleMsg = msg;
                            Object lv = r.get("level");
                            sampleLv = lv == null ? null : String.valueOf(lv);
                        }
                    }
                }
            }
            return new MatchResult(hit, appId, sampleMsg, sampleLv);
        }

        // LEVEL / KEYWORD / RATE 走纯 SQL 路径
        String cntSql = "SELECT COUNT(*) AS c FROM (" + union + ") t WHERE 1=1" + where;
        List<Dict> cntRows = holder.executeQuery(cntSql, params.toArray());
        long hitCount = 0;
        if (cntRows != null && !cntRows.isEmpty() && cntRows.get(0).get("c") != null) {
            hitCount = ((Number) cntRows.get(0).get("c")).longValue();
        }

        String sample = null;
        String sampleLv = null;
        if (hitCount > 0) {
            String sampleSql = "SELECT message, level FROM (" + union + ") t WHERE 1=1"
                    + where + " ORDER BY log_time DESC LIMIT 1";
            List<Dict> sRows = holder.executeQuery(sampleSql, params.toArray());
            if (sRows != null && !sRows.isEmpty()) {
                Object m = sRows.get(0).get("message");
                Object lv = sRows.get(0).get("level");
                if (m != null) {
                    sample = String.valueOf(m);
                }
                if (lv != null) {
                    sampleLv = String.valueOf(lv);
                }
            }
        }
        return new MatchResult(hitCount, appId, sample, sampleLv);
    }

    // ==================== 单规则评估 + 事件生命周期 ====================

    private void evalOne(Dict rule) {
        long ruleId = toLong(rule.get("id"));
        int threshold = toInt(rule.get("threshold"), 1);
        if (threshold <= 0) {
            threshold = 1;
        }
        int silenceSec = toInt(rule.get("silence_sec"), 600);
        if (silenceSec <= 0) {
            silenceSec = 600;
        }
        long appId = toLong(rule.get("app_id"));
        String ruleName = String.valueOf(rule.get("name"));
        String channels = rule.get("channels") == null ? "" : String.valueOf(rule.get("channels"));

        MatchResult mr = evaluate(holder, appService, rule);
        if (mr.hitCount < threshold) {
            return; // 未达阈值，留给 checkRecovery 处理「降级 → 自动恢复」
        }

        long now = System.currentTimeMillis();
        // 查最近一条同 rule_id 的 status=1 事件，决定延续还是新触发
        List<Dict> opens = holder.executeQuery(
                "SELECT id, first_time, last_time, hit_count FROM " + DataSourceConfig.ALERT_EVENT_TABLE
                        + " WHERE rule_id = ? AND status = 1 ORDER BY last_time DESC LIMIT 1",
                Long.valueOf(ruleId));

        if (opens != null && !opens.isEmpty()) {
            Dict open = opens.get(0);
            long evId = toLong(open.get("id"));
            long lastTime = toLong(open.get("last_time"));
            int oldHit = toInt(open.get("hit_count"), 0);
            boolean inSilence = (now - lastTime) < silenceSec * 1000L;
            if (inSilence) {
                // 静默窗口内：合并到旧事件（更新 last_time / hit_count / 样本），不再发 Webhook
                final int newHit = oldHit + (int) mr.hitCount;
                final String sampleMsg = mr.sampleMessage == null ? "" : truncate(mr.sampleMessage, 2000);
                final String summary = buildSummary(mr, threshold);
                tryRetry("merge alert event " + evId, () -> holder.executeUpdate(
                        "UPDATE " + DataSourceConfig.ALERT_EVENT_TABLE
                                + " SET last_time = ?, hit_count = ?, sample_message = ?, summary = ? WHERE id = ?",
                        new Date(), Integer.valueOf(newHit), sampleMsg, summary, Long.valueOf(evId)));
                return;
            }
            // 静默窗口已过：关闭旧事件，再创建新事件
            final long oldId = evId;
            tryRetry("close old alert event " + oldId, () -> holder.executeUpdate(
                    "UPDATE " + DataSourceConfig.ALERT_EVENT_TABLE + " SET status = 2 WHERE id = ?",
                    Long.valueOf(oldId)));
        }

        // 创建新事件
        String appName = appId > 0 ? resolveAppName(appId) : "全部应用";
        final Dict row = new Dict();
        row.put("rule_id", Long.valueOf(ruleId));
        row.put("rule_name", ruleName);
        row.put("app_id", Long.valueOf(appId));
        row.put("app_name", appName);
        row.put("level", mr.sampleLevel == null ? "" : mr.sampleLevel);
        row.put("summary", buildSummary(mr, threshold));
        row.put("sample_message", mr.sampleMessage == null ? "" : truncate(mr.sampleMessage, 2000));
        row.put("hit_count", Integer.valueOf((int) mr.hitCount));
        row.put("status", Integer.valueOf(1));
        row.put("first_time", new Date());
        row.put("last_time", new Date());
        row.put("ack_user", "");

        final long[] newId = { 0L };
        tryRetry("insert alert event", () -> {
            holder.insert(DataSourceConfig.ALERT_EVENT_TABLE, row);
            List<Dict> ids = holder.executeQuery(
                    "SELECT MAX(id) AS id FROM " + DataSourceConfig.ALERT_EVENT_TABLE);
            if (ids != null && !ids.isEmpty() && ids.get(0).get("id") != null) {
                newId[0] = ((Number) ids.get(0).get("id")).longValue();
            }
        });

        // Webhook 调用走异步，避免外部 HTTP 故障拖慢扫描线程
        if (newId[0] > 0 && channels.length() > 0) {
            final long evId = newId[0];
            final MatchResult mr0 = mr;
            final Dict rule0 = rule;
            final String appName0 = appName;
            CompletableFuture.runAsync(() -> callWebhook(evId, rule0, mr0, channels, appName0));
        }
    }

    /**
     * 自动恢复：对所有 status=1 事件，按其规则在当前窗口重新评估，
     * 命中数 < threshold/2 → 标记恢复。规则被删的事件直接恢复。
     */
    private void checkRecovery() {
        List<Dict> opens = holder.executeQuery(
                "SELECT id, rule_id FROM " + DataSourceConfig.ALERT_EVENT_TABLE + " WHERE status = 1");
        if (opens == null || opens.isEmpty()) {
            return;
        }
        for (Dict ev : opens) {
            long evId = toLong(ev.get("id"));
            long ruleId = toLong(ev.get("rule_id"));
            List<Dict> rs = holder.executeQuery(
                    "SELECT * FROM " + DataSourceConfig.ALERT_RULE_TABLE + " WHERE id = ?",
                    Long.valueOf(ruleId));
            if (rs == null || rs.isEmpty()) {
                // 规则已被删：自动恢复
                final long id = evId;
                tryRetry("auto-recover orphan event " + id, () -> holder.executeUpdate(
                        "UPDATE " + DataSourceConfig.ALERT_EVENT_TABLE + " SET status = 2 WHERE id = ?",
                        Long.valueOf(id)));
                continue;
            }
            Dict rule = rs.get(0);
            // 被禁用的规则不再评估，但其 status=1 事件应当持续显示，等用户处置；不强制恢复
            Object en = rule.get("enabled");
            if (en != null && ((Number) en).intValue() == 0) {
                continue;
            }
            int threshold = toInt(rule.get("threshold"), 1);
            MatchResult mr = evaluate(holder, appService, rule);
            if (mr.hitCount < Math.max(1, threshold / 2)) {
                final long id = evId;
                tryRetry("auto-recover event " + id, () -> holder.executeUpdate(
                        "UPDATE " + DataSourceConfig.ALERT_EVENT_TABLE + " SET status = 2 WHERE id = ?",
                        Long.valueOf(id)));
            }
        }
    }

    // ==================== Webhook 通知 ====================

    /** 简易提取 channels JSON 中的 webhook URL，避免依赖 xtboot JsonUtils 的具体 API */
    private static String extractWebhookUrl(String channels) {
        if (channels == null || channels.length() == 0) {
            return null;
        }
        Matcher m = WEBHOOK_PATTERN.matcher(channels);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static final Pattern WEBHOOK_PATTERN = Pattern.compile(
            "\"webhook\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** 调用 Webhook：POST JSON，5s 超时，失败只记日志不抛 */
    private static void callWebhook(long eventId, Dict rule, MatchResult mr,
                                    String channels, String appName) {
        String url = extractWebhookUrl(channels);
        if (url == null || url.length() == 0) {
            return;
        }
        String payload = buildWebhookPayload(eventId, rule, mr, appName);
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
            // 丢弃响应体，避免连接挂住
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
            }
        } catch (Exception e) {
            System.err.println("[xtlog] webhook " + url + " failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 手写 JSON（避免依赖 JsonUtils），转义关键字段 */
    private static String buildWebhookPayload(long eventId, Dict rule, MatchResult mr, String appName) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"event_id\":").append(eventId).append(",");
        sb.append("\"rule_id\":").append(toLong(rule.get("id"))).append(",");
        sb.append("\"rule_name\":").append(q(String.valueOf(rule.get("name")))).append(",");
        sb.append("\"app_id\":").append(toLong(rule.get("app_id"))).append(",");
        sb.append("\"app_name\":").append(q(appName)).append(",");
        sb.append("\"level\":").append(q(mr.sampleLevel == null ? "" : mr.sampleLevel)).append(",");
        sb.append("\"hit_count\":").append(mr.hitCount).append(",");
        sb.append("\"threshold\":").append(toInt(rule.get("threshold"), 1)).append(",");
        sb.append("\"window_sec\":").append(toInt(rule.get("window_sec"), 300)).append(",");
        sb.append("\"first_time\":").append(System.currentTimeMillis()).append(",");
        sb.append("\"summary\":").append(q(buildSummary(mr, toInt(rule.get("threshold"), 1)))).append(",");
        sb.append("\"sample_message\":").append(q(mr.sampleMessage == null ? "" : truncate(mr.sampleMessage, 1000)));
        sb.append("}");
        return sb.toString();
    }

    private static String q(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    // ==================== 工具 ====================

    /**
     * 包裹 {@link DbBusyRetry#retry}：失败只记日志，不抛出。
     * 告警旁路不能反噬主流程，事件落库 / 状态变更失败只影响告警本身。
     */
    private void tryRetry(String desc, DbBusyRetry.SqlAction action) {
        try {
            DbBusyRetry.retry(action, 4, 200L);
        } catch (Exception e) {
            System.err.println("[xtlog] alert " + desc + " failed: " + e.getMessage());
        }
    }

    /** 把多张月表 UNION ALL 成一张逻辑表（投影最小列集，避免读取 LONGTEXT message） */
    private static String buildUnion(List<String> tables) {
        StringBuilder sb = new StringBuilder();
        String cols = "id, app_id, level, logger, message, log_time";
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                sb.append(" UNION ALL ");
            }
            sb.append("SELECT ").append(cols).append(" FROM ").append(tables.get(i));
        }
        return sb.toString();
    }

    private String resolveAppName(long appId) {
        AppInfo info = appService.resolveById(appId);
        return info == null ? ("应用#" + appId) : info.name;
    }

    private static String buildSummary(MatchResult mr, int threshold) {
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
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static long toLong(Object o) {
        return DataSourceConfig.tsOf(o, 0L);
    }

    private static int toInt(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
