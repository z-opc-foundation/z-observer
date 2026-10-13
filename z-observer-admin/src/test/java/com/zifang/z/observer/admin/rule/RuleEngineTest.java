package com.zifang.z.observer.admin.rule;

import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.PrometheusQueryException;
import com.zifang.z.observer.admin.metric.Series;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    /** 可编排的假查询面：按 promql 配返回结果，也能让某次查询直接抛异常。 */
    private static final class FakeQuery implements MetricQuery {
        private final Map<String, List<Series>> answers = new HashMap<String, List<Series>>();
        private final Map<String, Boolean> failing = new HashMap<String, Boolean>();
        private int instantCalls;

        FakeQuery on(String promql, Series... series) {
            answers.put(promql, Arrays.asList(series));
            return this;
        }

        FakeQuery failing(String promql) {
            failing.put(promql, Boolean.TRUE);
            return this;
        }

        @Override
        public List<Series> instant(String promql, long timeMillis) {
            instantCalls++;
            if (Boolean.TRUE.equals(failing.get(promql))) {
                throw new PrometheusQueryException("模拟采集面故障: " + promql);
            }
            List<Series> s = answers.get(promql);
            return s == null ? Collections.<Series>emptyList() : s;
        }

        @Override
        public List<Series> range(String promql, long start, long end, long step) {
            return instant(promql, start);
        }

        @Override
        public long lastSeenMillis(String promql) {
            return -1;
        }
    }

    private static final class RecordingNotifier implements AlertNotifier {
        final List<String> events = new ArrayList<String>();

        @Override
        public void notifyFiring(Alert alert, Silence silencedBy) {
            events.add("FIRING " + alert.ruleId() + " " + alert.labels()
                    + (silencedBy == null ? "" : " silencedBy=" + silencedBy.id()));
        }

        @Override
        public void notifyResolved(Alert alert) {
            events.add("RESOLVED " + alert.ruleId() + " " + alert.labels());
        }
    }

    private static Series series(String app, double value) {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("app", app);
        return new Series(labels, value, 0);
    }

    private static Series series(String label, String value, double number) {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put(label, value);
        return new Series(labels, number, 0);
    }

    private FakeQuery query;
    private RecordingNotifier notifier;
    private RuleEngine engine;

    @BeforeEach
    void setUp() {
        query = new FakeQuery();
        notifier = new RecordingNotifier();
        engine = new RuleEngine(query, notifier);
    }

    private Rule thresholdRule(long forMillis) {
        return Rule.builder()
                .id("high-error-rate").name("错误率过高").severity("critical")
                .expression(Expression.THRESHOLD)
                .promql("error_rate")
                .condition(Condition.GT).threshold(0.05)
                .forDurationMillis(forMillis)
                .groupBy("app")
                .build();
    }

    @Test
    void 条件成立时先进PENDING不发通知() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(60_000));

        engine.evaluate(1000);

        assertThat(notifier.events).isEmpty();
        assertThat(engine.activeAlerts()).hasSize(1);
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.PENDING);
    }

    @Test
    void 确认时长不够不会升级为FIRING() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(60_000));

        engine.evaluate(1000);
        engine.evaluate(30_000);

        assertThat(notifier.events).isEmpty();
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.PENDING);
    }

    @Test
    void 连续成立够久才发通知() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(60_000));

        engine.evaluate(1000);
        engine.evaluate(61_000);

        assertThat(notifier.events).containsExactly(
                "FIRING high-error-rate {app=z-gw}");
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.FIRING);
    }

    @Test
    void PENDING期间条件消失则不发任何通知() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(60_000));
        engine.evaluate(1000);

        query.on("error_rate"); // 掉到阈值以下
        engine.evaluate(2000);

        // 一次抖动不该留下任何痕迹，更不该发恢复 —— 它从来没发出去过。
        assertThat(notifier.events).isEmpty();
        assertThat(engine.activeAlerts()).isEmpty();
    }

    @Test
    void 恢复后发恢复通知() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0));
        engine.evaluate(1000);
        assertThat(notifier.events).hasSize(1);

        query.on("error_rate", series("z-gw", 0.01));
        engine.evaluate(2000);

        assertThat(notifier.events).hasSize(2);
        assertThat(notifier.events.get(1)).startsWith("RESOLVED");
    }

    @Test
    void 持续成立不重复通知() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0));

        engine.evaluate(1000);
        engine.evaluate(2000);
        engine.evaluate(3000);

        assertThat(notifier.events).hasSize(1);
    }

    @Test
    void 确认时长为0时一轮就发() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0));
        engine.evaluate(1000);
        assertThat(notifier.events).hasSize(1);
    }

    @Test
    void 每个时序一条独立告警() {
        query.on("error_rate", series("z-gw", 0.5), series("z-order", 0.9));
        engine.registerRule(thresholdRule(0));

        engine.evaluate(1000);

        assertThat(notifier.events).hasSize(2);
    }

    // ---- absent：M1「进程挂了 60 秒内到人」的判据 ----

    @Test
    void absent在查不到数据时告警() {
        query.on("job:up"); // 什么都没配 = 空结果
        engine.registerRule(Rule.builder()
                .id("instance-down").name("实例失联").severity("critical")
                .expression(Expression.ABSENT)
                .promql("job:up")
                .forDurationMillis(0)
                .build());

        engine.evaluate(1000);

        assertThat(notifier.events).hasSize(1);
        assertThat(notifier.events.get(0)).startsWith("FIRING instance-down");
    }

    @Test
    void absent在有数据时不告警() {
        query.on("job:up", series("z-gw", 1));
        engine.registerRule(Rule.builder()
                .id("instance-down").name("实例失联")
                .expression(Expression.ABSENT).promql("job:up")
                .forDurationMillis(0).build());

        engine.evaluate(1000);

        assertThat(notifier.events).isEmpty();
    }

    /**
     * absent 不能写成 {@code up == 0}：进程彻底消失后 Prometheus 会因 staleness
     * 把时序列删掉，表达式返回空而不是 0，那条规则在最需要它的时候永远不成立。
     */
    @Test
    void 实例消失后阈值规则拿不到数据而不是拿到0() {
        FakeQuery q = new FakeQuery();
        RecordingNotifier n = new RecordingNotifier();
        RuleEngine e = new RuleEngine(q, n);
        q.on("up", series("z-gw", 0));
        e.registerRule(Rule.builder()
                .id("up-zero").name("up 为 0")
                .expression(Expression.THRESHOLD).promql("up")
                .condition(Condition.EQ).threshold(0).forDurationMillis(0).build());
        e.evaluate(1000);
        assertThat(n.events).hasSize(1);

        // 进程彻底消失 → 时序被删 → 查询返回空
        q.on("up");
        e.evaluate(2000);

        assertThat(n.events.get(1)).startsWith("RESOLVED");
    }

    // ---- 组合布尔 ----

    @Test
    void 组合布尔要求所有子条件对同一组标签同时成立() {
        query.on("error_rate", series("z-gw", 0.5), series("z-idle", 0.9));
        query.on("request_total", series("z-gw", 100), series("z-idle", 0));
        engine.registerRule(Rule.builder()
                .id("real-errors").name("真有请求在失败")
                .expression(Expression.BOOLEAN)
                .condition(new Rule.SubCondition("error_rate", Condition.GT, 0.05))
                .condition(new Rule.SubCondition("request_total", Condition.GT, 0))
                .forDurationMillis(0)
                .build());

        engine.evaluate(1000);

        // z-idle 错误率 0.9 但没流量 —— 半夜没流量的服务错误率恒为 1，
        // 单独告警会一直响。加上「有请求」之后才是真问题。
        assertThat(notifier.events).containsExactly("FIRING real-errors {app=z-gw}");
    }

    @Test
    void 组合布尔少于两个子条件直接报错() {
        try {
            Rule.builder().id("x").expression(Expression.BOOLEAN)
                    .condition(new Rule.SubCondition("a", Condition.GT, 1))
                    .build();
            org.junit.jupiter.api.Assertions.fail("应当拒绝只有一个子条件的布尔规则");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("至少要两个子条件");
        }
    }

    // ---- 去噪三件套 ----

    @Test
    void 静默压住通知但不改状态() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0));
        engine.addSilence(new Silence("s1", Arrays.asList("app=z-gw"),
                0, 10_000, "计划内停机", "zifang"));

        engine.evaluate(1000);

        assertThat(notifier.events).hasSize(1);
        assertThat(notifier.events.get(0)).contains("silencedBy=s1");
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.FIRING);
    }

    @Test
    void 静默过期后不再压() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0));
        engine.addSilence(new Silence("s1", Arrays.asList("app=z-gw"),
                0, 1500, "计划内停机", "zifang"));

        engine.evaluate(1000);
        query.on("error_rate");
        engine.evaluate(2000); // 恢复
        query.on("error_rate", series("z-gw", 0.5));
        engine.evaluate(3000); // 又来了

        assertThat(notifier.events.get(2)).doesNotContain("silencedBy");
    }

    @Test
    void 抑制规则压住目标告警() {
        query.on("cluster_up", series("cluster", "c1", 0));
        engine.registerRule(Rule.builder().id("cluster-down").name("集群不可用")
                .expression(Expression.THRESHOLD).promql("cluster_up")
                .condition(Condition.EQ).threshold(0).forDurationMillis(0)
                .groupBy("cluster")
                .build());

        // 集群级告警先起；此时 pod 级还没有数据
        engine.evaluate(1000);
        assertThat(notifier.events).containsExactly("FIRING cluster-down {cluster=c1}");

        engine.addInhibitRule(InhibitRule.sameLabelOn("i1",
                "cluster-down", "high-error-rate", "cluster"));

        // 集群不可用会连带把每个 pod 的错误率顶起来 —— 这正是要压掉的噪音
        query.on("error_rate", series("cluster", "c1", 0.5));
        engine.registerRule(thresholdRule(0));
        engine.evaluate(2000);

        assertThat(notifier.events).hasSize(1);
        assertThat(engine.activeAlerts()).hasSize(2);
    }

    @Test
    void 不在同一集群时抑制不生效() {
        query.on("cluster_up", series("cluster", "c1", 0));
        engine.registerRule(Rule.builder().id("cluster-down").name("集群不可用")
                .expression(Expression.THRESHOLD).promql("cluster_up")
                .condition(Condition.EQ).threshold(0).forDurationMillis(0)
                .groupBy("cluster").build());
        engine.addInhibitRule(InhibitRule.sameLabelOn("i1",
                "cluster-down", "high-error-rate", "cluster"));
        engine.evaluate(1000);

        // c2 集群的 pod 出问题，而 c1 才在告警 —— 这条必须真的通知出去
        query.on("error_rate", series("cluster", "c2", 0.5));
        engine.registerRule(thresholdRule(0));
        engine.evaluate(2000);

        assertThat(notifier.events).hasSize(2);
    }

    @Test
    void 抑制规则不允许自己抑制自己() {
        try {
            InhibitRule.any("i1", "same", "same");
            org.junit.jupiter.api.Assertions.fail("应当拒绝自抑制");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("不能与目标相同");
        }
    }

    @Test
    void 抑制在标签取值不同时不生效() {
        List<Alert> firing = Collections.singletonList(new Alert("cluster-down", "n", "critical",
                Collections.singletonMap("cluster", "c1"), null,
                AlertState.FIRING, 0, 0, null));
        InhibitRule rule = InhibitRule.sameLabelOn("i1", "cluster-down", "pod-down", "cluster");

        Alert otherCluster = new Alert("pod-down", "n", "critical",
                Collections.singletonMap("cluster", "c2"), null,
                AlertState.PENDING, 0, 0, null);

        assertThat(rule.suppresses(otherCluster, firing)).isFalse();
    }

    @Test
    void 标签缺失时不抑制() {
        List<Alert> firing = Collections.singletonList(new Alert("cluster-down", "n", "critical",
                Collections.<String, String>emptyMap(), null,
                AlertState.FIRING, 0, 0, null));
        InhibitRule rule = InhibitRule.sameLabelOn("i1", "cluster-down", "pod-down", "cluster");
        Alert target = new Alert("pod-down", "n", "critical",
                Collections.<String, String>emptyMap(), null,
                AlertState.PENDING, 0, 0, null);

        // 两边都没有 cluster 标签：宁可多叫一次，也不要凭空推断同一范围
        assertThat(rule.suppresses(target, firing)).isFalse();
    }

    // ---- 采集面故障 ----

    @Test
    void 查询失败时状态一个字都不动() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0));
        engine.evaluate(1000);
        assertThat(notifier.events).hasSize(1);

        query.failing("error_rate");
        engine.evaluate(2000);

        // 关键：Prometheus 自己抖一下，不能被读成「问题恢复了」
        assertThat(notifier.events).hasSize(1);
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.FIRING);
        assertThat(engine.evaluateErrorCount()).isEqualTo(1);
    }

    @Test
    void NaN取值不触发阈值告警() {
        query.on("error_rate", series("z-gw", Double.NaN));
        engine.registerRule(thresholdRule(0));
        engine.evaluate(1000);
        assertThat(notifier.events).isEmpty();
    }

    // ---- 分组 ----

    @Test
    void groupBy决定告警身份_无关标签变化不产生新告警() {
        Rule rule = thresholdRule(0);
        Map<String, String> first = new HashMap<String, String>();
        first.put("app", "z-gw");
        first.put("pod", "p1");
        Map<String, String> second = new HashMap<String, String>();
        second.put("app", "z-gw");
        second.put("pod", "p2"); // 同一个 app，不同 pod

        assertThat(fingerprintOf(rule, first)).isEqualTo(fingerprintOf(rule, second));

        // 不带 groupBy 时 pod 一变就是新告警
        Rule noGroup = Rule.builder().id("high-error-rate").promql("error_rate")
                .condition(Condition.GT).threshold(0.05).build();
        assertThat(fingerprintOf(noGroup, first)).isNotEqualTo(fingerprintOf(noGroup, second));
    }

    private static String fingerprintOf(Rule rule, Map<String, String> labels) {
        return new Alert(rule.id(), rule.name(), rule.severity(), labels, null,
                AlertState.OK, 0, 0, null).fingerprint(rule.groupBy());
    }
}
