package com.zifang.z.observer.admin.notify;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条通知的结构化载荷。
 *
 * <p><b>为什么不能只传 title + body。</b> 文案里那五项（应用名 / 实例 / 规则名 / 触发值 / 时间）
 * 不是拼出来的字符串，而是各自的字段：下游 z-msg 的模板要按字段渲染
 * （需求 009 D4：固定告警走模板审核流），人也要能一眼分辨「哪条告警、哪个实例」。
 * 把它们提前拼成一个字符串，模板侧就只剩 `${content}` 一坨，
 * 既没法按字段做样式/长度裁剪，也没法在结构化日志里检索。
 *
 * <p>字段取值全部来自告警实例本身，不含任何凭据。
 */
public final class AlertNotification {

    /** 通知类型。恢复通知必须能和告警通知区分开 —— 这是 M1 的验收项之一。 */
    public enum Kind {
        /** 告警正在触发。 */
        FIRING,
        /** 告警已恢复。 */
        RESOLVED,
        /** 告警被静默压住（不发渠道，但落记录）。 */
        SILENCED
    }

    private final Kind kind;
    private final String app;
    private final String instance;
    private final String ruleId;
    private final String ruleName;
    private final String severity;
    private final String value;
    private final long timestampMillis;
    private final long durationMillis;
    private final String title;
    private final String body;
    private final Map<String, String> labels;

    public AlertNotification(Kind kind, String app, String instance, String ruleId,
                             String ruleName, String severity, String value,
                             long timestampMillis, long durationMillis,
                             String title, String body, Map<String, String> labels) {
        this.kind = kind;
        this.app = app;
        this.instance = instance;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.severity = severity;
        this.value = value;
        this.timestampMillis = timestampMillis;
        this.durationMillis = durationMillis;
        this.title = title;
        this.body = body;
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                labels == null ? Collections.<String, String>emptyMap() : labels));
    }

    public Kind kind() {
        return kind;
    }

    /** 应用名。 */
    public String app() {
        return app;
    }

    /** 实例（pod / 主机 / 目标地址）。 */
    public String instance() {
        return instance;
    }

    public String ruleId() {
        return ruleId;
    }

    /** 规则名（人看的名字，不是 id）。 */
    public String ruleName() {
        return ruleName;
    }

    public String severity() {
        return severity;
    }

    /** 触发值。absent 类规则没有数值时为空。 */
    public String value() {
        return value;
    }

    /** 通知产生时刻（毫秒）。 */
    public long timestampMillis() {
        return timestampMillis;
    }

    /** 从告警开始到本通知的时长（毫秒）；恢复通知才有意义。 */
    public long durationMillis() {
        return durationMillis;
    }

    public String title() {
        return title;
    }

    public String body() {
        return body;
    }

    public Map<String, String> labels() {
        return labels;
    }

    /**
     * 给 z-msg 模板用的参数字典。
     *
     * <p>键名用英文（下划线小写），值是给人看的中文/原文。
     * 模板侧写 {@code ${app}} / {@code ${instance}} / {@code ${rule}} / {@code ${value}} /
     * {@code ${time}} / {@code ${kind}} 即可，不必知道收端内部怎么命名。
     */
    public Map<String, String> templateParams() {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("app", nullToDash(app));
        params.put("instance", nullToDash(instance));
        params.put("rule", nullToDash(ruleName));
        params.put("rule_id", nullToDash(ruleId));
        params.put("severity", nullToDash(severity));
        params.put("value", nullToDash(value));
        params.put("time", formatTime(timestampMillis));
        params.put("kind", kind.name());
        params.put("kind_text", kindText());
        if (durationMillis > 0) {
            params.put("duration", formatDuration(durationMillis));
        }
        params.put("title", nullToDash(title));
        params.put("content", nullToDash(body));
        return params;
    }

    private String kindText() {
        switch (kind) {
            case RESOLVED:
                return "恢复通知";
            case SILENCED:
                return "静默通知";
            case FIRING:
            default:
                return "告警通知";
        }
    }

    /** 人类可读时间。统一格式，模板与正文都用它。 */
    public static String formatTime(long millis) {
        if (millis <= 0) {
            return "-";
        }
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA);
        return fmt.format(new java.util.Date(millis));
    }

    /** 人类可读时长。 */
    public static String formatDuration(long millis) {
        if (millis <= 0) {
            return "0 秒";
        }
        long seconds = millis / 1000L;
        if (seconds < 60) {
            return seconds + " 秒";
        }
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        if (minutes < 60) {
            return minutes + " 分 " + rest + " 秒";
        }
        long hours = minutes / 60L;
        long restMin = minutes % 60L;
        return hours + " 小时 " + restMin + " 分 " + rest + " 秒";
    }

    private static String nullToDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }
}
