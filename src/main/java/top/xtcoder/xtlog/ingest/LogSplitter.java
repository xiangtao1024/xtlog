package top.xtcoder.xtlog.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import top.xtcoder.xtboot.core.Dict;

/**
 * 超长日志拆分器。
 *
 * <p>背景：单条日志偶尔会大到离谱（本库实测最大 7.6MB，多为异常把整个大对象 toString 出来
 * 或超长堆栈）。这样的行会带来一串连锁问题：单行撑爆写入批次、索引膨胀、
 * <code>LIKE</code> / 全文索引扫描成本被少数几条拉高、浏览器渲染一条日志就卡死。
 *
 * <p>这里不丢数据、也不截断尾部，而是把超长日志**切成多条连续的行**保存：
 * <ul>
 *   <li>同一条原始日志的所有片段共享一个 <code>cont_group</code>（20 位随机串）；</li>
 *   <li><code>cont_seq</code> 从 0 递增，0 是首段，之后每段都是上一段的继续；</li>
 *   <li>除 message 与分片字段外，各片段的其它属性（app / level / logger / thread /
 *       log_time / src_ip …）与首段完全一致，因此筛选、聚合、时间排序都不受影响。</li>
 * </ul>
 * 界面上据此显示「续第 N 段」标记，并提供按 <code>cont_group</code> 拼回原文。
 *
 * <p>切分规则：优先在换行处断开（不切断有意义的一行日志）；
 * 遇到单行本身就超过上限的情况才按字符硬切（避免单行使排行失效导致死循环）。
 */
public final class LogSplitter {

    /** 分片组标识的列名 */
    public static final String KEY_GROUP = "cont_group";

    /** 分片序号的列名 */
    public static final String KEY_SEQ = "cont_seq";

    private LogSplitter() {
    }

    /**
     * 按需把一个日志 Dict 展开成 1..n 条。
     *
     * <p>无论如何返回的每条 Dict 都会带齐 <code>cont_group</code> 与 <code>cont_seq</code>：
     * 同一批写入 Dict 的 key 集合必须一致，否则批量插入可能按首条的列组装 SQL，
     * 让后续缺少该 key 的行写不进去。
     * 未超限的日志 <code>cont_group</code> 为 null、<code>cont_seq</code> 为 0。
     *
     * @param d        原始日志（不会被修改）
     * @param maxChars 单条上限（字符数）；<= 0 表示不限制
     * @return 至少包含 1 条的列表
     */
    public static List<Dict> expand(Dict d, int maxChars) {
        Object msgObj = d.get("message");
        String message = msgObj == null ? "" : String.valueOf(msgObj);
        List<Dict> out = new ArrayList<Dict>(2);

        if (maxChars <= 0 || message.length() <= maxChars) {
            Dict single = copy(d);
            single.put(KEY_GROUP, null);
            single.put(KEY_SEQ, Integer.valueOf(0));
            out.add(single);
            return out;
        }

        List<String> parts = split(message, maxChars);
        // parts 至少有 2 段（length > maxChars 时必然如此），保险起见再兜一层
        if (parts.size() <= 1) {
            Dict single = copy(d);
            single.put(KEY_GROUP, null);
            single.put(KEY_SEQ, Integer.valueOf(0));
            out.add(single);
            return out;
        }

        String group = newGroupId();
        for (int i = 0; i < parts.size(); i++) {
            Dict seg = copy(d);
            seg.put("message", parts.get(i));
            seg.put(KEY_GROUP, group);
            seg.put(KEY_SEQ, Integer.valueOf(i));
            out.add(seg);
        }
        return out;
    }

    /** 切文本：优先在换行处断，单行超限时硬切 */
    public static List<String> split(String text, int maxChars) {
        List<String> parts = new ArrayList<String>();
        int total = text.length();
        if (total == 0) {
            parts.add(text);
            return parts;
        }
        StringBuilder cur = new StringBuilder(Math.min(total, maxChars + 256));
        int i = 0;
        while (i < total) {
            int nl = text.indexOf('\n', i);
            int lineEnd = nl < 0 ? total : nl + 1;
            int lineLen = lineEnd - i;

            if (lineLen > maxChars) {
                // 单行比上限还长：先把攒下的吐出来，再按字符硬切，
                // 否则「当前段 + 这一行永远超限」会导致段无限增大或死循环
                if (cur.length() > 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
                parts.add(text.substring(i, i + maxChars));
                i += maxChars;
                continue;
            }
            if (cur.length() > 0 && cur.length() + lineLen > maxChars) {
                parts.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(text, i, lineEnd);
            i = lineEnd;
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        if (parts.isEmpty()) {
            parts.add(text);
        }
        return parts;
    }

    /** 20 位随机串，用于把同一条日志的各个片段串起来 */
    private static String newGroupId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static Dict copy(Dict src) {
        Dict n = new Dict();
        for (Object k : src.keySet()) {
            if (k != null) {
                n.put(String.valueOf(k), src.get(String.valueOf(k)));
            }
        }
        return n;
    }
}
