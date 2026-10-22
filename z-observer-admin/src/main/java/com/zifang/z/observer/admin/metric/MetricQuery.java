package com.zifang.z.observer.admin.metric;

import java.util.List;

/**
 * 指标查询面。
 *
 * <p>抽成接口是为了 D2 的过渡形态：M1 先读 z-env 已有的 Prometheus 本体，
 * 终态换成自研轻 TSDB 时<b>只换实现</b>（实现本接口即可），
 * 规则引擎、控制台、REST 层一行不改。
 *
 * <p><b>「查不到」和「查到 0」是两回事</b>，接口用空列表表达前者。混淆这两者会让
 * absent 告警永远不触发（空列表被当成 0，0 又不等于 absent），而这正是 M1
 * 「进程挂了 60 秒内到人」的验收口径所依赖的判据。
 *
 * <p><b>三种语义</b>，对应 PromQL 的三种结果形态：
 * <ul>
 *   <li><b>瞬时值</b>（{@link #instant}）：某一时刻的一组时序取值 → 向量。</li>
 *   <li><b>区间向量</b>（{@link #range}）：一段时间内的一组时序取值 → 矩阵。</li>
 *   <li><b>标量</b>（{@link #scalar}）：单个数（无标签）→ 标量。</li>
 * </ul>
 *
 * <p><b>支持的表达式形态（PromQL 子集）</b>。适配层<b>不解析 PromQL</b>，
 * 整串透传给 Prometheus，所以语法上 Prometheus 收的都收；下面列的是
 * <b>规则引擎实际用到的子集</b>，也是自研 TSDB 实现本接口时必须能覆盖的：
 * <ul>
 *   <li>单指标名：{@code z_observer_frames_total}</li>
 *   <li>标签匹配（{@code =} {@code !=} {@code =~} {@code !~}）：
 *       {@code up{job="z-observer"}}、{@code up{job=~"z-.*"}}</li>
 *   <li>区间向量函数：{@code rate(m[5m])}、{@code irate(m[5m])}、
 *       {@code increase(m[1h])}、{@code delta(m[1h])}</li>
 *   <li>聚合：{@code sum(m)}、{@code count(m)}、{@code avg(m)}、{@code max(m)}、
 *       {@code min(m)}，以及带 by 的 {@code sum by (job) (m)}</li>
 *   <li>标量：{@code scalar(m)}、常量算术（{@code 1+1}）</li>
 *   <li>比较与布尔组合：{@code m > 0}、{@code m1 > 0 and m2 > 0}
 *       （规则引擎的 BOOLEAN 表达式按「同一组标签同时成立」实现）</li>
 * </ul>
 * <b>不做</b>：子查询（{@code m[5m:1m]}）、{@code offset} / {@code @} 修饰符、
 * recording rule 引用、{@code label_replace} 等改标签的函数 —— 规则引擎用不上，
 * 自研 TSDB 也不必为它们实现解析。
 *
 * <p><b>失败语义（降级口径）</b>：查询请求本身失败（网络不可达、HTTP 错误码、
 * 响应不合法、PromQL 语法错）必须<b>抛异常</b>，不能返回空列表 ——
 * 空列表会被规则引擎读成「指标不存在」，于是一次网络抖动变成一批 absent 告警，
 * 监控系统在自己最需要它的时候坏掉，还顺手喊醒一批人。
 */
public interface MetricQuery {

    /** 即时查询。返回空列表表示没有这条时序（target 挂了 / 指标还没产生过）。 */
    List<Series> instant(String promql, long timeMillis);

    /** 区间查询。 */
    List<Series> range(String promql, long startMillis, long endMillis, long stepMillis);

    /**
     * 标量查询：表达式必须只返回<b>一个数</b>。
     *
     * <p>默认实现由 {@link #instant} 折算（恰好一条时序取其值、无结果记 NaN、
     * 多条视为表达式不合法）。实现方若能直接拿到标量结果形态，应当覆写它 ——
     * Prometheus 的 {@code scalar(...)} 走的正是那条路。
     *
     * @return 标量值；表达式没有结果时是 {@link Double#NaN}
     */
    default double scalar(String promql, long timeMillis) {
        List<Series> one = instant(promql, timeMillis);
        if (one.isEmpty()) {
            return Double.NaN;
        }
        if (one.size() > 1) {
            throw new PrometheusQueryException(
                    "标量查询返回了 " + one.size() + " 条时序，表达式不是标量: " + promql);
        }
        return one.get(0).value();
    }

    /**
     * 某条时序最近一次被采集到的时间；查不到返回 -1。
     *
     * <p>absent 判定要用这个而不是「instant 查询有没有结果」：
     * 查询表达式本身写错、或者表达式匹配到别的时序时，instant 也会返回空，
     * 两种情况会被当成同一个「实例挂了」通知出去。
     */
    long lastSeenMillis(String promql);
}
