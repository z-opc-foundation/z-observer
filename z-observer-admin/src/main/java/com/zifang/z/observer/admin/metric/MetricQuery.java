package com.zifang.z.observer.admin.metric;

import java.util.List;

/**
 * 指标查询面。
 *
 * <p>抽成接口是为了 D2 的过渡形态：M1 先读 z-env 已有的 Prometheus 本体，
 * 终态换成自研轻 TSDB 时只换实现，规则引擎、控制台、REST 层一行不改。
 *
 * <p><b>「查不到」和「查到 0」是两回事</b>，接口用空列表表达前者。混淆这两者会让
 * absent 告警永远不触发（空列表被当成 0，0 又不等于 absent），而这正是 M1
 * 「进程挂了 60 秒内到人」的验收口径所依赖的判据。
 */
public interface MetricQuery {

    /** 即时查询。返回空列表表示没有这条时序（target 挂了 / 指标还没产生过）。 */
    List<Series> instant(String promql, long timeMillis);

    /** 区间查询。 */
    List<Series> range(String promql, long startMillis, long endMillis, long stepMillis);

    /**
     * 某条时序最近一次被采集到的时间；查不到返回 -1。
     *
     * <p>absent 判定要用这个而不是「instant 查询有没有结果」：
     * 查询表达式本身写错、或者表达式匹配到别的时序时，instant 也会返回空，
     * 两种情况会被当成同一个「实例挂了」通知出去。
     */
    long lastSeenMillis(String promql);
}
