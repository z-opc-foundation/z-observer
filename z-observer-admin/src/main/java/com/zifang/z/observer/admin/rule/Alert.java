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

    public Alert(String ruleId, String ruleName, String severity,
                 Map<String, String> labels, Map<String, String> annotations,
                 AlertState state, long activeSinceMillis, long lastEvaluateMillis,
                 String value) {
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
    }

    public Alert withState(AlertState newState, long nowMillis) {
        return new Alert(ruleId, ruleName, severity, labels, annotations,
                newState, activeSinceMillis, nowMillis, value);
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
