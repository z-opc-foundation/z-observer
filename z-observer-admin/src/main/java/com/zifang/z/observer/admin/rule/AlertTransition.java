package com.zifang.z.observer.admin.rule;

/**
 * 告警状态机的一次状态变迁。
 *
 * <p><b>它是「判定链」的最小单位。</b> 引擎每推一格就记一条，
 * 于是「某条告警为什么在第 3 轮才发出去」可以被完整回放：
 * 哪一轮进了 PENDING、确认计数攒到几、第几轮才进 FIRING。
 *
 * <p>为什么需要它：状态机跑在定时器里，出问题时人手里只有
 * 「现在有一条告警在响」这个结论，看不到过程。
 * 而<b>过程恰恰是判断误报还是真故障的唯一依据</b> ——
 * 抖动误报和真故障在最终状态上长得一模一样。
 */
public final class AlertTransition {

    private final String ruleId;
    private final String fingerprint;
    private final AlertState from;
    private final AlertState to;
    private final long atMillis;
    private final int hitCount;
    private final String reason;

    public AlertTransition(String ruleId, String fingerprint, AlertState from, AlertState to,
                           long atMillis, int hitCount, String reason) {
        this.ruleId = ruleId;
        this.fingerprint = fingerprint;
        this.from = from;
        this.to = to;
        this.atMillis = atMillis;
        this.hitCount = hitCount;
        this.reason = reason == null ? "" : reason;
    }

    public String ruleId() {
        return ruleId;
    }

    /** 告警实例标识（ruleId + group_by 标签），同一实例的变迁串成一条链。 */
    public String fingerprint() {
        return fingerprint;
    }

    public AlertState from() {
        return from;
    }

    public AlertState to() {
        return to;
    }

    public long atMillis() {
        return atMillis;
    }

    /** 变迁发生时的<b>连续命中次数</b>（两段确认的计数闸门状态）。 */
    public int hitCount() {
        return hitCount;
    }

    /** 人话：这一步为什么发生。 */
    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return ruleId + " " + from + "->" + to + " hits=" + hitCount + " @" + atMillis
                + " " + reason;
    }
}
