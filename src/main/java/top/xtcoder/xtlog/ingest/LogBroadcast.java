package top.xtcoder.xtlog.ingest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import top.xtcoder.xtboot.context.annotation.Component;
import top.xtcoder.xtboot.core.Dict;
import top.xtcoder.xtboot.web.sse.SseEmitter;

/**
 * SSE 日志广播器：按 appId 分组维护活跃连接，新日志入队时实时推送。
 * key 为 appId，特殊 key "*" 表示订阅全部应用。
 */
@Component
public class LogBroadcast {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>();

    public void subscribe(String appId, SseEmitter emitter) {
        String key = (appId == null || appId.isEmpty()) ? "*" : appId;
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(key);
        if (list == null) {
            list = new CopyOnWriteArrayList<SseEmitter>();
            CopyOnWriteArrayList<SseEmitter> prev = subscribers.putIfAbsent(key, list);
            if (prev != null) {
                list = prev;
            }
        }
        list.add(emitter);
    }

    /** 新日志到达：推送给对应 appId 的订阅者 + 订阅全部的订阅者 */
    public void onLog(Dict log) {
        Object appIdObj = log.get("app_id");
        String appId = appIdObj == null ? null : String.valueOf(appIdObj);
        push(appId, log);
        push("*", log);
    }

    private void push(String key, Dict log) {
        if (key == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(key);
        if (list == null) {
            return;
        }
        for (SseEmitter em : list) {
            try {
                em.send(log);
            } catch (Exception e) {
                // 连接已断开由 onDisconnect 清理，此处忽略
            }
        }
    }

    public void remove(String appId, SseEmitter emitter) {
        String key = (appId == null || appId.isEmpty()) ? "*" : appId;
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(key);
        if (list != null) {
            list.remove(emitter);
        }
    }

    public int getSubscriberCount() {
        int n = 0;
        for (CopyOnWriteArrayList<SseEmitter> list : subscribers.values()) {
            n += list.size();
        }
        return n;
    }
}
