package com.zifang.z.observer.admin.notify;

import com.zifang.z.observer.admin.rule.Alert;
import com.zifang.z.observer.admin.rule.AlertState;
import com.zifang.z.observer.admin.rule.Silence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertDispatcherTest {

    private static Alert alert(String severity) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("cluster", "c1");
        return new Alert("r1", "订单超时率", severity, labels,
                Collections.<String, String>emptyMap(), AlertState.FIRING, 1000L, 2000L,
                "0.42");
    }

    private static AlertDispatcher dispatcher(Map<String, List<MessageChannel>> map) {
        return new AlertDispatcher(map);
    }

    private static Map<String, List<MessageChannel>> one(MessageChannel c) {
        Map<String, List<MessageChannel>> m = new LinkedHashMap<String, List<MessageChannel>>();
        m.put("*", new ArrayList<MessageChannel>(Arrays.asList(c)));
        return m;
    }

    @Test
    void 空渠道配置直接拒() {
        assertThatThrownBy(() -> new AlertDispatcher(
                new LinkedHashMap<String, List<MessageChannel>>()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 按severity选渠道_没配的回落到通配() {
        RecordingChannel critical = new RecordingChannel("ding");
        RecordingChannel fallback = new RecordingChannel("log");
        Map<String, List<MessageChannel>> m = new LinkedHashMap<String, List<MessageChannel>>();
        m.put("critical", new ArrayList<MessageChannel>(Arrays.asList(critical)));
        m.put("*", new ArrayList<MessageChannel>(Arrays.asList(fallback)));

        AlertDispatcher d = dispatcher(m);
        d.notifyFiring(alert("critical"), null);
        d.notifyFiring(alert("warning"), null);

        assertThat(critical.sent).hasSize(1);
        assertThat(fallback.sent).hasSize(1);
        assertThat(fallback.sent.get(0)).contains("[WARNING]");
    }

    @Test
    void 一个渠道挂掉不影响别的渠道_但要计数() {
        MessageChannel boom = new MessageChannel() {
            @Override
            public String name() {
                return "sms";
            }

            @Override
            public void send(String title, String body) {
                throw new NotifyException("短信网关 503");
            }
        };
        RecordingChannel ok = new RecordingChannel("ding");
        Map<String, List<MessageChannel>> m = new LinkedHashMap<String, List<MessageChannel>>();
        m.put("*", new ArrayList<MessageChannel>(Arrays.asList(boom, ok)));

        AlertDispatcher d = dispatcher(m);
        d.notifyFiring(alert("critical"), null);

        assertThat(ok.sent).hasSize(1);
        assertThat(d.sentCount()).isEqualTo(1);
        assertThat(d.failedCount()).isEqualTo(1);
    }

    @Test
    void 静默压住通知但仍要落记录() {
        RecordingChannel c = new RecordingChannel("ding");
        AlertDispatcher d = dispatcher(one(c));
        Silence silence = new Silence("s1", Arrays.asList("cluster=c1"),
                0L, Long.MAX_VALUE, "发布窗口", "ceo");

        d.notifyFiring(alert("critical"), silence);

        assertThat(c.sent).isEmpty();
        assertThat(d.sentCount()).isZero();
        assertThat(d.silencedCount()).isEqualTo(1);
        // 关键：事后还能回答「昨晚为什么没人收到」
        assertThat(d.records()).hasSize(1);
        assertThat(d.records().get(0).silenced()).isTrue();
        // 标题负责让人一眼认出「这是被压住的」，静默原因落在正文里
        assertThat(d.records().get(0).title()).contains("[静默]");
    }

    @Test
    void 恢复通知走同一渠道且不计静默() {
        RecordingChannel c = new RecordingChannel("ding");
        AlertDispatcher d = dispatcher(one(c));
        d.notifyResolved(alert("critical"));
        assertThat(c.sent.get(0)).contains("[恢复]");
        assertThat(d.sentCount()).isEqualTo(1);
        assertThat(d.silencedCount()).isZero();
    }

    @Test
    void 记录条数有上界_不会把内存吃光() {
        RecordingChannel c = new RecordingChannel("ding");
        AlertDispatcher d = new AlertDispatcher(one(c), 3);
        for (int i = 0; i < 10; i++) {
            d.notifyFiring(alert("warning"), null);
        }
        assertThat(d.records()).hasSize(3);
        assertThat(d.sentCount()).isEqualTo(10);
    }

    @Test
    void 通知正文带上取值标签与开始时间() {
        RecordingChannel c = new RecordingChannel("ding");
        dispatcher(one(c)).notifyFiring(alert("critical"), null);
        String body = c.sent.get(0);
        assertThat(body).contains("规则: r1")
                .contains("取值: 0.42")
                .contains("cluster=c1")
                .contains("开始于: 1000");
    }

    private static final class RecordingChannel implements MessageChannel {
        private final String name;
        private final List<String> sent = new ArrayList<String>();

        RecordingChannel(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void send(String title, String body) {
            sent.add(title + "\n" + body);
        }
    }
}