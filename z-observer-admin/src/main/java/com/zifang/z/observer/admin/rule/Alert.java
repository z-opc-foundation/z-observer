package com.zifang.z.observer.admin.rule;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条告警实例。
 *
 * <p>「实例」而不是「规则」：一条规则命中 200 个 pod 就是 200 条告警，各自带各自的
 * 标签。通知、按实例抑制、恢复都作用在实例上。
 */
public final class Alert {

    private final String ruleId;
    private final String ruleName;
    private final String severity;
    private final Map<String, String> labels;
    private final Map<String, String> annotations;
    private final AlertState state;
    private final long activeSinceMillis;
    private final long lastEvaluateMillis;
    private final String value;
    private final int hitCount;

    public Alert(String ruleId, String ruleName, String severity,
                 Map<String, String> labels, Map<String, String> annotations,
                 AlertState state, long activeSinceMillis, long lastEvaluateMillis,
                 String value) {
        this(ruleId, ruleName, severity, labels, annotations, state,
                activeSinceMillis, lastEvaluateMillis, value, 0);
    }

    /**
     * @param hitCount <b>连续命中的评估周期数</b>（两段确认的计数闸门状态）。
     *                 0 表示「还没进入待确认」；进入 PENDING 后每命中一轮 +1，
     *                 条件断一轮就随记录一起作废（见 {@link RuleEngine}）。
     */
    public Alert(String ruleId, String ruleName, String severity,
                 Map<String, String> labels, Map<String, String> annotations,
                 AlertState state, long activeSinceMillis, long lastEvaluateMillis,
                 String value, int hitCount) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.severity = severity;
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                labels == null ? Collections.<String, String>emptyMap() : labels));
        this.annotations = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                annotations == null ? Collections.<String, String>emptyMap() : annotations));
        this.state = state;
        this.activeSinceMillis = activeSinceMillis;
        this.lastEvaluateMillis = lastEvaluateMillis;
        this.value = value;
        this.hitCount = hitCount;
    }

    public Alert withState(AlertState newState, long nowMillis) {
        return new Alert(ruleId, ruleName, severity, labels, annotations,
                newState, activeSinceMillis, nowMillis, value, hitCount);
    }

    /**
     * 连续命中计数 +1（仍在 PENDING 时用）。
     *
     * <p>计数是<b>两段确认的计数闸门</b>：连续命中 {@code forHits} 个周期才进 FIRING。
     * 条件断一轮时整条记录作废 ⇒ 计数从 0 重来 ⇒ 抖动永远攒不满。
     */
    public Alert withHitCount(int newHitCount, long nowMillis) {
        return new Alert(ruleId, ruleName, severity, labels, annotations,
                state, activeSinceMillis, nowMillis, value, newHitCount);
    }

    public String ruleId() {
        return ruleId;
    }

    public String ruleName() {
        return ruleName;
    }

    public String severity() {
        return severity;
    }

    public Map<String, String> labels() {
        return labels;
    }

    public Map<String, String> annotations() {
        return annotations;
    }

    public AlertState state() {
        return state;
    }

    public long activeSinceMillis() {
        return activeSinceMillis;
    }

    public long lastEvaluateMillis() {
        return lastEvaluateMillis;
    }

    /** 触发时的取值文本，进通知正文。没有数值（absent）时为空。 */
    public String value() {
        return value;
    }

    /**
     * 连续命中的评估周期数（两段确认的计数闸门状态）。
     *
     * <p>取值 0 = 还没进过待确认；进了 PENDING 之后每命中一轮 +1。
     * 条件断一轮 → 整条记录作废 → 计数归零重来 ⇒ 「命中-未命中交替」攒不满，
     * 于是永远进不了 FIRING。
     */
    public int hitCount() {
        return hitCount;
    }

    /**
     * 告警实例的唯一标识。
     *
     * <p>含 ruleId：两条规则命中同一个 pod 是两件事，必须能分开。
     * 标签部分只取 groupBy 指定的那些 —— 用全量标签会让一个无关标签的取值变化
     * 变成「一条告警恢复了、另一条新告警升起」，通知看起来像在闪。
     */
    public String fingerprint(java.util.List<String> groupBy) {
        StringBuilder sb = new StringBuilder(ruleId);
        java.util.List<String> names = groupBy == null || groupBy.isEmpty()
                ? new java.util.ArrayList<String>(labels.keySet())
                : new java.util.ArrayList<String>(groupBy);
        Collections.sort(names);
        for (String n : names) {
            sb.append('|').append(n).append('=').append(labels.get(n));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return ruleId + " " + state + " " + labels;
    }
}
