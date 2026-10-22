package com.zifang.z.observer.admin.config;

import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.Series;
import com.zifang.z.observer.admin.notify.AlertDispatcher;
import com.zifang.z.observer.admin.notify.MessageChannel;
import com.zifang.z.observer.admin.rule.AlertState;
import com.zifang.z.observer.admin.rule.AlertTransition;
import com.zifang.z.observer.admin.rule.Expression;
import com.zifang.z.observer.admin.rule.Rule;
import com.zifang.z.observer.admin.rule.RuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三类规则各配一条真实规则，并用<b>注入数据</b>验证判定正确。
 *
 * <p>本卡第 1 项要的不是「yml 里写了三条规则」，而是「这三条规则在数据进去之后
 * 判得对」。所以这里的规则对象是<b>真容器里装出来的那三条</b>（不是测试里手搓的
 * {@code Rule}），数据是注入的假查询面 ⇒ 验的是「配置 + 引擎」这一整段的判定。
 *
 * <p><b>每条用例只把被测规则装进引擎。</b> 三条真规则同时装进一个引擎时，
 * 「缺席规则」会在别的用例里也命中（查不到 {@code up{job="z-observer"}} 就是缺席），
 * 计数会互相串味 —— 串味了就读不出「这条规则判得对不对」。
 * 规则之间互不干扰这件事由 {@link #三条真规则同装时各自独立命中()} 单独钉住。
 *
 * <p>三类各配两条断言，正反都验：
 * <ul>
 *   <li>阈值（{@code collector_target_down}）：up 为 0 ⇒ 命中；up 为 1 ⇒ 不命中。</li>
 *   <li>缺席（{@code process_absent}）：查不到时序 ⇒ 命中；查得到 ⇒ 不命中。</li>
 *   <li>组合布尔（{@code observer_frames_rejected}）：两个子条件都成立 ⇒ 命中；
 *       任一子条件不成立 ⇒ 不命中。</li>
 * </ul>
 *
 * <p>「不该触发」的那一半和「该触发」的那一半同样重要 ——
 * 只会发不会拦的告警系统，等于把噪音源接上了电话。
 */
@SpringBootTest(classes = RealRuleInjectionTest.TestApp.class, properties = {
        "z.observer.collector-port=0",
        "z.observer.prometheus-url=http://127.0.0.1:1",
        "z.observer.scrape-interval-millis=3600000",
        "z.observer.evaluate-interval-millis=3600000",
        "z.observer.scrape-urls=http://127.0.0.1:1/metrics"
})
class RealRuleInjectionTest {

    private static final String ABSENT_RULE = "process_absent";
    private static final String THRESHOLD_RULE = "collector_target_down";
    private static final String BOOLEAN_RULE = "observer_frames_rejected";

    @SpringBootApplication
    @Import(ObserverAdminConfiguration.class)
    static class TestApp {
    }

    /** 录制渠道：只看「有没有真的发出去」。 */
    static final class RecordingChannel implements MessageChannel {
        final List<String> titles = new ArrayList<String>();

        @Override
        public String name() {
            return "rec";
        }

        @Override
        public void send(String title, String body) {
            titles.add(title);
        }
    }

    /** 可编排的假查询面：按 promql 配返回结果。 */
    static final class FakeQuery implements MetricQuery {
        private final Map<String, List<Series>> answers = new HashMap<String, List<Series>>();

        FakeQuery on(String promql, Series... series) {
            answers.put(promql, Arrays.asList(series));
            return this;
        }

        @Override
        public List<Series> instant(String promql, long timeMillis) {
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

    private static Series series(String... labelPairs) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < labelPairs.length; i += 2) {
            labels.put(labelPairs[i], labelPairs[i + 1]);
        }
        return new Series(labels, Double.parseDouble(labelPairs[labelPairs.length - 1]), 0);
    }

    @Autowired
    private RuleEngine containerEngine;

    private FakeQuery query;
    private RecordingChannel channel;

    @BeforeEach
    void setUp() {
        query = new FakeQuery();
        channel = new RecordingChannel();
    }

    /**
     * 只装一条规则的引擎。
     *
     * <p>规则对象取自<b>真容器</b>（yml 装配出来的那个实例），所以验的是
     * 「配置 + 引擎」整段，而不是测试里手搓的规则。
     */
    private RuleEngine engineFor(String ruleId) {
        RuleEngine engine = new RuleEngine(query, new AlertDispatcher(channelsOf()));
        engine.registerRule(realRule(ruleId));
        return engine;
    }

    /** 装上全部三条真规则的引擎。 */
    private RuleEngine engineWithAllRealRules() {
        RuleEngine engine = new RuleEngine(query, new AlertDispatcher(channelsOf()));
        for (Rule r : containerEngine.rules()) {
            engine.registerRule(r);
        }
        return engine;
    }

    private Map<String, List<MessageChannel>> channelsOf() {
        Map<String, List<MessageChannel>> channels =
                new LinkedHashMap<String, List<MessageChannel>>();
        channels.put("*", Collections.<MessageChannel>singletonList(channel));
        return channels;
    }

    /** 真容器里装出来的那条规则（不可变，直接拿来用）。 */
    private Rule realRule(String ruleId) {
        for (Rule r : containerEngine.rules()) {
            if (r.id().equals(ruleId)) {
                return r;
            }
        }
        throw new AssertionError("真规则里找不到 " + ruleId
                + "（实际装了：" + containerEngine.rules() + "）");
    }

    /**
     * 跑够轮次让两把闸门都过（确认时长 30s + 连续命中 2 次）。
     *
     * <p>真实规则都是「确认时长 30s + 连续命中 2 次」⇒ 至少要跨过 30s。
     * 第 1 轮只够进 PENDING（计数 1/2），第 2 轮才可能进 FIRING。
     */
    private int settle(RuleEngine engine, long startMillis) {
        engine.evaluate(startMillis);
        return engine.evaluate(startMillis + 60_000L);
    }

    /** 某条规则的状态轨迹（跳过原地踏步的 PENDING→PENDING）。 */
    private List<String> statePath(RuleEngine engine, String ruleId) {
        List<String> path = new ArrayList<String>();
        for (AlertTransition t : engine.transitions()) {
            if (!t.ruleId().equals(ruleId) || t.from() == t.to()) {
                continue;
            }
            String step = t.from() + "->" + t.to();
            if (path.isEmpty() || !path.get(path.size() - 1).equals(step)) {
                path.add(step);
            }
        }
        return path;
    }

    // ---- 阈值类 ----

    @Test
    void 阈值规则_up为0时命中() {
        query.on("up{job=~\"z-.*\"}", series("job", "z-gw", "instance", "10.0.0.1:8080", "0"));

        RuleEngine engine = engineFor(THRESHOLD_RULE);
        int sent = settle(engine, 1_000);

        assertThat(sent).isEqualTo(1);
        assertThat(channel.titles).hasSize(1);
        assertThat(channel.titles.get(0)).contains("采集目标不可达");
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.FIRING);
        assertThat(statePath(engine, THRESHOLD_RULE)).containsExactly("OK->PENDING", "PENDING->FIRING");
    }

    @Test
    void 阈值规则_up为1时不命中() {
        query.on("up{job=~\"z-.*\"}", series("job", "z-gw", "instance", "10.0.0.1:8080", "1"));

        RuleEngine engine = engineFor(THRESHOLD_RULE);
        int sent = settle(engine, 1_000);

        assertThat(sent).isZero();
        assertThat(channel.titles).isEmpty();
        assertThat(engine.activeAlerts()).as("没命中就不该有活跃告警").isEmpty();
    }

    // ---- 缺席类 ----

    @Test
    void 缺席规则_查不到时序时命中() {
        // 什么都不配 = 查询结果为空 = 时序被 Prometheus 因 staleness 删掉了
        RuleEngine engine = engineFor(ABSENT_RULE);
        int sent = settle(engine, 1_000);

        assertThat(sent).isEqualTo(1);
        assertThat(channel.titles).hasSize(1);
        assertThat(channel.titles.get(0)).contains("进程消失");
        assertThat(statePath(engine, ABSENT_RULE)).containsExactly("OK->PENDING", "PENDING->FIRING");
    }

    @Test
    void 缺席规则_查得到时序时不命中() {
        query.on("up{job=\"z-observer\"}", series("job", "z-observer", "instance", "10.0.0.1:8080", "1"));

        RuleEngine engine = engineFor(ABSENT_RULE);
        int sent = settle(engine, 1_000);

        assertThat(sent).isZero();
        assertThat(channel.titles).isEmpty();
        assertThat(engine.activeAlerts()).isEmpty();
    }

    // ---- 组合布尔类 ----

    @Test
    void 组合布尔规则_两个子条件都成立时命中() {
        query.on("rate(z_observer_frames_total[5m])",
                series("job", "z-observer", "instance", "10.0.0.1:8080", "12.5"));
        query.on("rate(z_observer_frames_rejected_total[5m])",
                series("job", "z-observer", "instance", "10.0.0.1:8080", "0.4"));

        RuleEngine engine = engineFor(BOOLEAN_RULE);
        int sent = settle(engine, 1_000);

        assertThat(sent).isEqualTo(1);
        assertThat(channel.titles).hasSize(1);
        assertThat(channel.titles.get(0)).contains("帧被拒");
        assertThat(statePath(engine, BOOLEAN_RULE)).containsExactly("OK->PENDING", "PENDING->FIRING");
    }

    /** 反向钉：任一子条件不成立 ⇒ 整条规则不命中。 */
    @Test
    void 组合布尔规则_任一子条件不成立时不命中() {
        // 有流量、但没有坏帧
        query.on("rate(z_observer_frames_total[5m])",
                series("job", "z-observer", "instance", "10.0.0.1:8080", "12.5"));
        query.on("rate(z_observer_frames_rejected_total[5m])",
                series("job", "z-observer", "instance", "10.0.0.1:8080", "0"));

        RuleEngine engine = engineFor(BOOLEAN_RULE);
        int sent = settle(engine, 1_000);

        assertThat(sent).isZero();
        assertThat(channel.titles).isEmpty();
        assertThat(engine.activeAlerts()).isEmpty();
    }

    /** 反向钉：子条件成立但标签对不上 ⇒ 交集为空 ⇒ 不命中。 */
    @Test
    void 组合布尔规则_标签对不上时不命中() {
        query.on("rate(z_observer_frames_total[5m])",
                series("job", "z-observer", "instance", "10.0.0.1:8080", "12.5"));
        query.on("rate(z_observer_frames_rejected_total[5m])",
                series("job", "z-observer", "instance", "10.0.0.2:8080", "0.4"));

        RuleEngine engine = engineFor(BOOLEAN_RULE);
        int sent = settle(engine, 1_000);

        assertThat(sent).isZero();
        assertThat(channel.titles).isEmpty();
        assertThat(engine.activeAlerts()).isEmpty();
    }

    // ---- 三类齐全 & 共存 ----

    @Test
    void 真配置里三类规则各有一条() {
        List<Expression> types = new ArrayList<Expression>();
        for (Rule r : containerEngine.rules()) {
            if (!types.contains(r.expression())) {
                types.add(r.expression());
            }
        }
        assertThat(types).contains(Expression.THRESHOLD, Expression.ABSENT, Expression.BOOLEAN);
    }

    /**
     * 三条真规则都配了两把闸门：时长 30s + <b>连续命中 2 次</b>。
     *
     * <p>这是第 2 项「两段确认」在<b>真实配置</b>上的落点：
     * 计数闸门不是只在测试里手搓的规则上有，yml 里配的规则也有。
     */
    @Test
    void 三条真规则都配了连续命中2次的计数闸门() {
        for (String id : new String[]{THRESHOLD_RULE, ABSENT_RULE, BOOLEAN_RULE}) {
            Rule r = realRule(id);
            assertThat(r.forHits()).as("%s 的计数闸门", id).isEqualTo(2);
            assertThat(r.forDurationMillis()).as("%s 的时长闸门", id).isEqualTo(30_000L);
        }
    }

    /**
     * 三条真规则同装一个引擎：各自独立命中，互不串扰。
     *
     * <p>缺席规则「查不到 {@code up{job="z-observer"}}」在本用例里就是缺席 ⇒ 命中；
     * 阈值规则 up=0 ⇒ 命中；组合布尔两个子条件都成立 ⇒ 命中。
     * 三条同时发，说明真配置里这三条规则能各走各的判定链。
     */
    @Test
    void 三条真规则同装时各自独立命中() {
        query.on("up{job=~\"z-.*\"}", series("job", "z-gw", "instance", "10.0.0.1:8080", "0"));
        query.on("rate(z_observer_frames_total[5m])",
                series("job", "z-observer", "instance", "10.0.0.1:8080", "12.5"));
        query.on("rate(z_observer_frames_rejected_total[5m])",
                series("job", "z-observer", "instance", "10.0.0.1:8080", "0.4"));

        RuleEngine engine = engineWithAllRealRules();
        int sent = settle(engine, 1_000);

        assertThat(sent).isEqualTo(3);
        assertThat(channel.titles).hasSize(3);
        assertThat(channel.titles.toString())
                .contains("采集目标不可达")
                .contains("进程消失")
                .contains("帧被拒");
    }
}
