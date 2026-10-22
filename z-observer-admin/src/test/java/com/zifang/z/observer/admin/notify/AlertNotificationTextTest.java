package com.zifang.z.observer.admin.notify;

import com.zifang.z.observer.admin.rule.Alert;
import com.zifang.z.observer.admin.rule.AlertState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 验收第 2 条：<b>告警文案含应用名 / 实例 / 规则名 / 触发值 / 时间，恢复通知可区分。</b>
 *
 * <p>这几项每少一项，值班的人收到通知后第一件事就是回问「是哪个实例」——
 * 那条回问的时间通常比故障本身还长。所以它们在这里被钉死。
 */
class AlertNotificationTextTest {

    private static Alert alert(String ruleId, String ruleName, String severity,
                                Map<String, String> labels, String value,
                                AlertState state, long activeSince) {
        return new Alert(ruleId, ruleName, severity, labels,
                Collections.<String, String>emptyMap(), state, activeSince,
                System.currentTimeMillis(), value);
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("job", "z-ctc-admin");
        labels.put("instance", "z-ctc-admin-7d9f8c6b5d-abcde");
        labels.put("cluster", "c1");
        return labels;
    }

    private static final class RecordingChannel implements MessageChannel {
        private final List<AlertNotification> sent = new ArrayList<AlertNotification>();

        @Override
        public String name() {
            return "rec";
        }

        @Override
        public void send(String title, String body) {
            throw new IllegalStateException("应当走结构化通道");
        }

        @Override
        public void send(AlertNotification n) {
            sent.add(n);
        }
    }

    private static AlertDispatcher dispatcher(MessageChannel c) {
        Map<String, List<MessageChannel>> m = new LinkedHashMap<String, List<MessageChannel>>();
        m.put("*", new ArrayList<MessageChannel>(Arrays.asList(c)));
        return new AlertDispatcher(m);
    }

    @Test
    void 告警文案含应用名实例规则名触发值时间() {
        RecordingChannel c = new RecordingChannel();
        dispatcher(c).notifyFiring(alert("process_absent", "进程消失", "critical",
                labels(), "0", AlertState.FIRING, 1_700_000_000_000L), null);

        AlertNotification n = c.sent.get(0);
        String body = n.body();

        assertThat(body).contains("应用: z-ctc-admin");
        assertThat(body).contains("实例: z-ctc-admin-7d9f8c6b5d-abcde");
        assertThat(body).contains("规则: process_absent（进程消失）");
        assertThat(body).contains("取值: 0");
        assertThat(body).contains("通知时间: ");
        // 标题里也要能一眼看出是哪个应用的哪个实例
        assertThat(n.title()).contains("应用=z-ctc-admin")
                .contains("实例=z-ctc-admin-7d9f8c6b5d-abcde");
    }

    @Test
    void 恢复通知与告警通知能区分开() {
        RecordingChannel c = new RecordingChannel();
        AlertDispatcher d = dispatcher(c);

        d.notifyFiring(alert("process_absent", "进程消失", "critical",
                labels(), "0", AlertState.FIRING, 1_700_000_000_000L), null);
        d.notifyResolved(alert("process_absent", "进程消失", "critical",
                labels(), "0", AlertState.RESOLVED, 1_700_000_000_000L));

        AlertNotification firing = c.sent.get(0);
        AlertNotification resolved = c.sent.get(1);

        assertThat(firing.title()).contains("[告警]").doesNotContain("[恢复]");
        assertThat(resolved.title()).contains("[恢复]").doesNotContain("[告警]");
        assertThat(firing.body()).contains("通知类型: 告警通知（正在触发）");
        assertThat(resolved.body()).contains("通知类型: 恢复通知（告警已消除）");
        // 恢复通知还要看得出这条挂了多少时间
        assertThat(resolved.body()).contains("持续时长: ");
    }

    @Test
    void 模板参数把五项分别交出去而不是一坨字符串() {
        RecordingChannel c = new RecordingChannel();
        dispatcher(c).notifyFiring(alert("process_absent", "进程消失", "critical",
                labels(), "0", AlertState.FIRING, 1_700_000_000_000L), null);

        Map<String, String> params = c.sent.get(0).templateParams();
        assertThat(params).containsEntry("app", "z-ctc-admin")
                .containsEntry("instance", "z-ctc-admin-7d9f8c6b5d-abcde")
                .containsEntry("rule", "进程消失")
                .containsEntry("rule_id", "process_absent")
                .containsEntry("value", "0")
                .containsEntry("kind", "FIRING");
        assertThat(params.get("time")).isNotEmpty();
    }

    @Test
    void 标签里没有应用名时用规则id顶替_不留空() {
        RecordingChannel c = new RecordingChannel();
        dispatcher(c).notifyFiring(alert("process_absent", "进程消失", "critical",
                Collections.<String, String>emptyMap(), null,
                AlertState.FIRING, 1_700_000_000_000L), null);

        assertThat(c.sent.get(0).app()).isEqualTo("process_absent");
        assertThat(c.sent.get(0).body()).contains("取值: -");
    }
}
