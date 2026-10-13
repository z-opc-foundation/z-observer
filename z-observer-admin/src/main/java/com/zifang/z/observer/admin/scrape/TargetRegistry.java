package com.zifang.z.observer.admin.scrape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集目标登记处：把「发现源这一轮报了什么」变成「哪些 target 该采、哪些刚消失」。
 *
 * <p><b>为什么必须记住消失的 target。</b> z-config naming 是注册制服务发现：进程挂了
 * 会自己注销 / 心跳超时被摘掉，于是它从发现结果里<b>直接消失</b>。如果只保留当前列表，
 * 一个挂掉的进程就等于「从来没存在过」，absent 告警永远等不到它 —— 而「进程挂了 60 秒内
 * 到人」正是 M1 的验收口径。所以这里保留一份「曾经见过、现在不见了」的集合，
 * 交给规则引擎按 absent 去告警。
 *
 * <p>反过来，发现源自身故障（z-config 挂了/网络不通）时<b>不能</b>当成「全部实例消失」：
 * 那会一次性造出全集群的 absent 告警，比不告警更糟。所以发现失败一律抛异常，
 * 由调用方保留上一轮的集合。
 */
public final class TargetRegistry {

    private final Map<String, ScrapeTarget> live = new LinkedHashMap<String, ScrapeTarget>();
    private final Map<String, Long> vanishedSince = new LinkedHashMap<String, Long>();
    private final long retentionMillis;

    /**
     * 消失后保留多久。
     *
     * <p>留一段时间是为了让「恢复」也有对象可对：进程回来了要能消掉那条 absent 告警。
     * 但也不能永久留 —— 否则某服务下线半年后每次评估都还在扫一条幽灵记录。
     */
    public static final long DEFAULT_VANISHED_RETENTION_MILLIS = 30 * 60 * 1000L;

    public TargetRegistry() {
        this(DEFAULT_VANISHED_RETENTION_MILLIS);
    }

    public TargetRegistry(long retentionMillis) {
        this.retentionMillis = retentionMillis;
    }

    /**
     * 用一轮发现结果刷新。
     *
     * @param discovered 本轮发现到的 target；发现源故障时调用方<b>不要</b>调这个方法
     * @param nowMillis  当前时间
     * @return 本轮新消失的 target（第一次被记为消失的才算，重复发现源抖动不会反复通知）
     */
    public synchronized List<ScrapeTarget> refresh(List<ScrapeTarget> discovered, long nowMillis) {
        Map<String, ScrapeTarget> next = new LinkedHashMap<String, ScrapeTarget>();
        for (ScrapeTarget t : discovered) {
            next.put(t.id(), t);
        }
        List<ScrapeTarget> newlyVanished = new ArrayList<ScrapeTarget>();
        for (Map.Entry<String, ScrapeTarget> e : live.entrySet()) {
            if (!next.containsKey(e.getKey())) {
                vanishedSince.put(e.getKey(), Long.valueOf(nowMillis));
                newlyVanished.add(e.getValue());
            }
        }
        for (String id : next.keySet()) {
            // 又出现了就销掉消失记录，否则它会一直留在 absent 名单里被反复告警
            vanishedSince.remove(id);
        }
        live.clear();
        live.putAll(next);
        purgeVanished(nowMillis);
        return newlyVanished;
    }

    private void purgeVanished(long nowMillis) {
        List<String> expired = new ArrayList<String>();
        for (Map.Entry<String, Long> e : vanishedSince.entrySet()) {
            if (nowMillis - e.getValue().longValue() > retentionMillis) {
                expired.add(e.getKey());
            }
        }
        for (String id : expired) {
            vanishedSince.remove(id);
        }
    }

    /** 当前该采的 target。 */
    public synchronized List<ScrapeTarget> live() {
        return new ArrayList<ScrapeTarget>(live.values());
    }

    /** 已经消失、但仍需按 absent 盯着的 target。 */
    public synchronized List<ScrapeTarget> vanished() {
        List<ScrapeTarget> out = new ArrayList<ScrapeTarget>();
        for (String id : vanishedSince.keySet()) {
            ScrapeTarget t = live.get(id);
            out.add(t == null ? new ScrapeTarget(id, "http://vanished/" + id) : t);
        }
        return out;
    }

    public synchronized boolean isVanished(String id) {
        return vanishedSince.containsKey(id);
    }

    public synchronized int liveCount() {
        return live.size();
    }

    public synchronized int vanishedCount() {
        return vanishedSince.size();
    }

    public synchronized Map<String, Long> vanishedSinceSnapshot() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<String, Long>(vanishedSince));
    }
}