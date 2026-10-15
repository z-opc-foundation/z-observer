package com.zifang.z.observer.admin.collect;

import com.zifang.util.json.JsonCursor;
import com.zifang.util.json.JsonUtil;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 心跳登记：谁还活着。
 *
 * <p><b>判活靠心跳，不靠「有没有数据」。</b> 一个服务健康地不打印日志、不发指标，
 * 在「按数据判活」的口径下就是死的 —— 然后会收到一条莫名其妙的告警。
 * 心跳是显式的、无条件的，正好补上这个洞。
 *
 * <p><b>上一次心跳的时间就是判活依据</b>，不看「有没有批次」：D1 定了常驻服务走 pull，
 * 日志/链路是 push 边车，两个通道的可用性互相不能替代。
 */
public final class HeartbeatTracker {

    public static final long DEFAULT_ALIVE_TIMEOUT_MILLIS = 90_000L;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final long aliveTimeoutMillis;
    private final Map<String, Long> lastBeat = new LinkedHashMap<String, Long>();

    public HeartbeatTracker() {
        this(DEFAULT_ALIVE_TIMEOUT_MILLIS);
    }

    public HeartbeatTracker(long aliveTimeoutMillis) {
        if (aliveTimeoutMillis <= 0) {
            throw new IllegalArgumentException("aliveTimeoutMillis 必须为正: " + aliveTimeoutMillis);
        }
        this.aliveTimeoutMillis = aliveTimeoutMillis;
    }

    /**
     * 处理一次心跳。body 是 JSON 对象，至少带 instanceId。
     *
     * <p>解析失败不抛：心跳只是一条旁路信息，它坏了不该把收端的 IO 线程带崩。
     * 但也不静默 —— 解析不出 instanceId 就无法登记，只能跳过。
     */
    public void beat(byte[] body) {
        String instanceId = extractInstanceId(body);
        if (instanceId == null || instanceId.isEmpty()) {
            return;
        }
        synchronized (this) {
            lastBeat.put(instanceId, Long.valueOf(System.currentTimeMillis()));
        }
    }

    private String extractInstanceId(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonCursor node = JsonCursor.of(JsonUtil.parse(new String(body, UTF8)));
            JsonCursor id = node.path("instanceId");
            if (id.isMissing() || id.isNull()) {
                return null;
            }
            return id.asText();
        } catch (RuntimeException e) {
            // JsonUtil 抛的是非受检异常（JsonParseException），签名不再是 IOException
            return null;
        }
    }

    /** 某个实例是否还活着。 */
    public synchronized boolean isAlive(String instanceId, long nowMillis) {
        Long beat = lastBeat.get(instanceId);
        return beat != null && nowMillis - beat.longValue() <= aliveTimeoutMillis;
    }

    public synchronized boolean isAlive(String instanceId) {
        return isAlive(instanceId, System.currentTimeMillis());
    }

    /** 活着的实例列表。 */
    public synchronized List<String> aliveInstances(long nowMillis) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Long> e : lastBeat.entrySet()) {
            if (nowMillis - e.getValue().longValue() <= aliveTimeoutMillis) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 失联（曾心跳过但已超时）的实例。absent 告警的原料。 */
    public synchronized List<String> silentInstances(long nowMillis) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Long> e : lastBeat.entrySet()) {
            if (nowMillis - e.getValue().longValue() > aliveTimeoutMillis) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public synchronized long lastBeatMillis(String instanceId) {
        Long v = lastBeat.get(instanceId);
        return v == null ? 0L : v.longValue();
    }

    public synchronized int trackedCount() {
        return lastBeat.size();
    }

    public synchronized void forget(String instanceId) {
        lastBeat.remove(instanceId);
    }

    public synchronized Map<String, Long> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Long>(lastBeat));
    }
}
