package com.zifang.z.observer.admin.notify;

import com.zifang.z.observer.admin.rule.Alert;
import com.zifang.z.observer.admin.rule.AlertNotifier;
import com.zifang.z.observer.admin.rule.Silence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警通知出口实现。
 *
 * <p>多渠道扇出：severity 决定发到哪儿（critical 发全部，warning 只发主渠道）。
 * 一个渠道失败不影响其他渠道 —— 短信通道挂了不该让钉钉那条也发不出去 ——
 * 但失败一定计数并留在记录里。
 *
 * <p><b>被静默压住的通知照样落记录。</b> 只发一条「已静默」就完事的话，
 * 事后没人能回答「昨晚那个错为什么没人收到」。落记录是静默能成立的前提。
 */
public final class AlertDispatcher implements AlertNotifier {

    /** 应用名的标签候选（按优先级）。 */
    private static final String[] APP_LABELS =
            {"app", "application", "app_name", "job", "service", "target"};

    /** 实例的标签候选（按优先级）。 */
    private static final String[] INSTANCE_LABELS =
            {"instance", "pod", "pod_name", "host", "hostname", "container"};

    private final Map<String, List<MessageChannel>> severityChannels;
    private final List<NotificationRecord> records =
            Collections.synchronizedList(new ArrayList<NotificationRecord>());
    private final int maxRecords;

    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong silencedCount = new AtomicLong();

    public AlertDispatcher(Map<String, List<MessageChannel>> severityChannels) {
        this(severityChannels, 1000);
    }

    public AlertDispatcher(Map<String, List<MessageChannel>> severityChannels, int maxRecords) {
        if (severityChannels == null || severityChannels.isEmpty()) {
            throw new IllegalArgumentException("至少要配一个通知渠道");
        }
        this.severityChannels = severityChannels;
        this.maxRecords = maxRecords;
    }

    @Override
    public void notifyFiring(Alert alert, Silence silencedBy) {
        boolean silenced = silencedBy != null;
        long now = System.currentTimeMillis();
        AlertNotification.Kind kind = silenced
                ? AlertNotification.Kind.SILENCED : AlertNotification.Kind.FIRING;
        String title = (silenced ? "[静默] " : "[告警] ")
                + severityMark(alert.severity()) + " " + alert.ruleName()
                + " | 应用=" + appOf(alert) + " 实例=" + instanceOf(alert);
        String body = render(alert, silenced ? silencedBy.describe() : null, kind, now);

        record(new NotificationRecord(alert.fingerprint(Collections.<String>emptyList()),
                alert.ruleId(), alert.state().name(), title, silenced, now));

        if (silenced) {
            silencedCount.incrementAndGet();
            return;
        }
        fanout(alert, kind, title, body, now);
    }

    @Override
    public void notifyResolved(Alert alert) {
        long now = System.currentTimeMillis();
        String title = "[恢复] " + severityMark(alert.severity()) + " " + alert.ruleName()
                + " | 应用=" + appOf(alert) + " 实例=" + instanceOf(alert);
        String body = render(alert, null, AlertNotification.Kind.RESOLVED, now);

        record(new NotificationRecord(alert.fingerprint(Collections.<String>emptyList()),
                alert.ruleId(), alert.state().name(), title, false, now));

        fanout(alert, AlertNotification.Kind.RESOLVED, title, body, now);
    }

    private void fanout(Alert alert, AlertNotification.Kind kind, String title, String body, long now) {
        List<MessageChannel> channels = channelsFor(alert.severity());
        if (channels.isEmpty()) {
            failedCount.incrementAndGet();
            return;
        }
        AlertNotification notification = new AlertNotification(kind, appOf(alert), instanceOf(alert),
                alert.ruleId(), alert.ruleName(), alert.severity(), alert.value(),
                now, durationOf(alert, now), title, body, alert.labels());
        for (MessageChannel channel : channels) {
            try {
                channel.send(notification);
                sentCount.incrementAndGet();
            } catch (RuntimeException e) {
                // 一个渠道挂掉不影响别的渠道，但一定计数
                failedCount.incrementAndGet();
            }
        }
    }

    /** 从告警开始到现在的时长。恢复通知用。 */
    private static long durationOf(Alert alert, long now) {
        long since = alert.activeSinceMillis();
        return since > 0 && now > since ? now - since : 0L;
    }

    private List<MessageChannel> channelsFor(String severity) {
        List<MessageChannel> exact = severityChannels.get(severity);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }
        // 没给这个 severity 单独配就用通配的，别让新加一个 severity 变成静默不通知
        List<MessageChannel> fallback = severityChannels.get("*");
        return fallback == null ? Collections.<MessageChannel>emptyList() : fallback;
    }

    private static String severityMark(String severity) {
        if (severity == null || severity.isEmpty()) {
            return "UNKNOWN";
        }
        return "[" + severity.toUpperCase() + "]";
    }

    /**
     * 通知正文。
     *
     * <p>M1 验收要求的五项一个都不能少：<b>应用名 / 实例 / 规则名 / 触发值 / 时间</b>。
     * 少任何一项，值班的人收到短信后第一件事就是回问「是哪个实例」——
     * 那条回问的时间通常比故障本身还长。
     *
     * <p>恢复通知与告警通知必须能区分：标题前缀（{@code [告警]} / {@code [恢复]}）
     * 加上正文里的「通知类型」两行，缺一不可 —— 标题会被渠道截断，
     * 只有标题能区分的话，短信被截到只剩前半句时两条就长得一样了。
     */
    private static String render(Alert alert, String silencedNote,
                                 AlertNotification.Kind kind, long now) {
        StringBuilder sb = new StringBuilder();
        sb.append("通知类型: ").append(kindText(kind)).append('\n');
        sb.append("规则: ").append(alert.ruleId());
        if (alert.ruleName() != null && !alert.ruleName().isEmpty()) {
            sb.append("（").append(alert.ruleName()).append('）');
        }
        sb.append('\n');
        sb.append("严重级别: ").append(alert.severity()).append('\n');
        sb.append("状态: ").append(alert.state()).append('\n');
        sb.append("应用: ").append(appOf(alert)).append('\n');
        sb.append("实例: ").append(instanceOf(alert)).append('\n');
        sb.append("取值: ").append(alert.value() == null ? "-" : alert.value()).append('\n');
        if (!alert.labels().isEmpty()) {
            sb.append("标签:");
            for (Map.Entry<String, String> e : alert.labels().entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append('\n');
        }
        if (alert.activeSinceMillis() > 0) {
            sb.append("开始于: ").append(alert.activeSinceMillis())
                    .append(" (").append(AlertNotification.formatTime(alert.activeSinceMillis()))
                    .append(")\n");
            long duration = durationOf(alert, now);
            if (duration > 0) {
                sb.append("持续时长: ").append(AlertNotification.formatDuration(duration)).append('\n');
            }
        }
        sb.append("通知时间: ").append(AlertNotification.formatTime(now)).append('\n');
        if (silencedNote != null) {
            sb.append(silencedNote).append('\n');
        }
        return sb.toString();
    }

    private static String kindText(AlertNotification.Kind kind) {
        switch (kind) {
            case RESOLVED:
                return "恢复通知（告警已消除）";
            case SILENCED:
                return "静默通知（已压住，未发出）";
            case FIRING:
            default:
                return "告警通知（正在触发）";
        }
    }

    /**
     * 应用名：从告警标签里取。
     *
     * <p>标签名不统一是事实（{@code job} / {@code app} / {@code application} 都有人用），
     * 所以按优先级挨个找；都找不到就用规则 id 顶替 —— 宁可给个能定位的下界，
     * 也不要留空让收到告警的人以为「应用名没取到，是系统坏了」。
     */
    static String appOf(Alert alert) {
        String v = firstLabel(alert, APP_LABELS);
        return v != null ? v : alert.ruleId();
    }

    /** 实例：从告警标签里取（pod / 主机 / 目标地址）。 */
    static String instanceOf(Alert alert) {
        String v = firstLabel(alert, INSTANCE_LABELS);
        return v != null ? v : "-";
    }

    private static String firstLabel(Alert alert, String[] keys) {
        for (String k : keys) {
            String v = alert.labels().get(k);
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private void record(NotificationRecord r) {
        records.add(r);
        while (records.size() > maxRecords) {
            records.remove(0);
        }
    }

    public List<NotificationRecord> records() {
        synchronized (records) {
            return new ArrayList<NotificationRecord>(records);
        }
    }

    public long sentCount() {
        return sentCount.get();
    }

    public long failedCount() {
        return failedCount.get();
    }

    public long silencedCount() {
        return silencedCount.get();
    }

    /** 一条通知的落地记录。 */
    public static final class NotificationRecord {
        private final String fingerprint;
        private final String ruleId;
        private final String state;
        private final String title;
        private final boolean silenced;
        private final long timestampMillis;

        NotificationRecord(String fingerprint, String ruleId, String state,
                           String title, boolean silenced, long timestampMillis) {
            this.fingerprint = fingerprint;
            this.ruleId = ruleId;
            this.state = state;
            this.title = title;
            this.silenced = silenced;
            this.timestampMillis = timestampMillis;
        }

        public String fingerprint() {
            return fingerprint;
        }

        public String ruleId() {
            return ruleId;
        }

        public String state() {
            return state;
        }

        public String title() {
            return title;
        }

        public boolean silenced() {
            return silenced;
        }

        public long timestampMillis() {
            return timestampMillis;
        }
    }
}
