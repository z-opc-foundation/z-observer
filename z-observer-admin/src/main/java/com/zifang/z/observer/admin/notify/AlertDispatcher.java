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
        String title = (silenced ? "[静默] " : "[告警] ")
                + severityMark(alert.severity()) + " " + alert.ruleName();
        String body = render(alert, silenced ? silencedBy.describe() : null);

        record(new NotificationRecord(alert.fingerprint(Collections.<String>emptyList()),
                alert.ruleId(), alert.state().name(), title, silenced,
                System.currentTimeMillis()));

        if (silenced) {
            silencedCount.incrementAndGet();
            return;
        }
        fanout(alert.severity(), title, body);
    }

    @Override
    public void notifyResolved(Alert alert) {
        String title = "[恢复] " + severityMark(alert.severity()) + " " + alert.ruleName();
        String body = render(alert, null);

        record(new NotificationRecord(alert.fingerprint(Collections.<String>emptyList()),
                alert.ruleId(), alert.state().name(), title, false,
                System.currentTimeMillis()));

        fanout(alert.severity(), title, body);
    }

    private void fanout(String severity, String title, String body) {
        List<MessageChannel> channels = channelsFor(severity);
        if (channels.isEmpty()) {
            failedCount.incrementAndGet();
            return;
        }
        for (MessageChannel channel : channels) {
            try {
                channel.send(title, body);
                sentCount.incrementAndGet();
            } catch (RuntimeException e) {
                // 一个渠道挂掉不影响别的渠道，但一定计数
                failedCount.incrementAndGet();
            }
        }
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

    private static String render(Alert alert, String silencedNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("规则: ").append(alert.ruleId()).append('\n');
        sb.append("状态: ").append(alert.state()).append('\n');
        if (alert.value() != null) {
            sb.append("取值: ").append(alert.value()).append('\n');
        }
        if (!alert.labels().isEmpty()) {
            sb.append("标签:");
            for (Map.Entry<String, String> e : alert.labels().entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append('\n');
        }
        if (alert.activeSinceMillis() > 0) {
            sb.append("开始于: ").append(alert.activeSinceMillis()).append('\n');
        }
        if (silencedNote != null) {
            sb.append(silencedNote).append('\n');
        }
        return sb.toString();
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
