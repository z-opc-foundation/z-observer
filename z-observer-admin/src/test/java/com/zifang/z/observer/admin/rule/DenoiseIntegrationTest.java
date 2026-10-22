package com.zifang.z.observer.admin.rule;

import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.Series;
import com.zifang.z.observer.admin.notify.AlertDispatcher;
import com.zifang.z.observer.admin.notify.MessageChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 去噪三件套的集成验证用例：group_by / inhibit / silence 各一条。
 *
 * <p>本卡第 3 项要的是<b>集成</b>用例，不是单元级断言。所以这里跑的是
 * 「真引擎 + 真 AlertDispatcher + 真渠道扇出」的完整链路：
 * 规则 → 评估 → 状态机 → 去噪 → 通知正文。断言的对象是<b>渠道真正收到什么</b>，
 * 而不是引擎内部某个字段 —— 内部字段对了但通知发不出去，是最常见的坏法。
 *
 * <p>每条用例都配一条<b>反向钉</b>（不该发生的那个分支必须不发生）：
 * <ul>
 *   <li>group_by：同组聚合为一条；<b>同组换 pod 不产生第二条</b>。</li>
 *   <li>inhibit：源告警存在时目标被压住；<b>源不在同一范围时不压</b>。</li>
 *   <li>silence：窗口内压住且只记不发；<b>窗口外正常发</b>。</li>
 * </ul>
 *
 * <p><b>口径说明（别当成 bug 读）</b>：静默与抑制都<b>只压通知，不改状态机</b> ——
 * 被压住的告警在引擎里仍然是 FIRING（README「告警」一节写明这条），
 * 压住的是「发出去」这件事。断言因此落在渠道收到什么，而不是引擎内部状态。
 */
class DenoiseIntegrationTest {

    /** 录制渠道：记录渠道真正收到的标题。 */
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

    /** 可编排的假查询面。 */
    private static final class FakeQuery implements MetricQuery {
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

    private static Series series(double value, String... labelPairs) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < labelPairs.length; i += 2) {
            labels.put(labelPairs[i], labelPairs[i + 1]);
        }
        return new Series(labels, value, 0);
    }

    private FakeQuery query;
    private RecordingChannel channel;
    private AlertDispatcher dispatcher;
    private RuleEngine engine;

    @BeforeEach
    void setUp() {
        query = new FakeQuery();
        channel = new RecordingChannel();
        Map<String, List<MessageChannel>> channels =
                new LinkedHashMap<String, List<MessageChannel>>();
        channels.put("*", Collections.<MessageChannel>singletonList(channel));
        dispatcher = new AlertDispatcher(channels);
        engine = new RuleEngine(query, dispatcher);
    }

    /** 告警通知（[告警] 开头）。恢复通知（[恢复]）另算。 */
    private List<String> firingTitles() {
        List<String> out = new ArrayList<String>();
        for (String t : channel.titles) {
            if (t.startsWith("[告警]")) {
                out.add(t);
            }
        }
        return out;
    }

    /** 恢复通知（[恢复] 开头）。 */
    private List<String> resolvedTitles() {
        List<String> out = new ArrayList<String>();
        for (String t : channel.titles) {
            if (t.startsWith("[恢复]")) {
                out.add(t);
            }
        }
        return out;
    }

    private Rule errorRateRule(String id, String name) {
        return Rule.builder()
                .id(id).name(name)
                .expression(Expression.THRESHOLD).promql("error_rate")
                .condition(Condition.GT).threshold(0.05)
                .forDurationMillis(0).forHits(1)
                .groupBy("app")
                .build();
    }

    /**
     * group_by：同组告警聚合为<b>一条</b>通知。
     *
     * <p>反向钉：同组内换 pod（无关标签变化）<b>不</b>产生第二条通知 ——
     * 一旦 fingerprint 退化成全量标签，pod 一变就变成「一条恢复了、另一条新升起」，
     * 通知看起来像在闪。
     */
    @Test
    void group_by_同组聚合为一条通知() {
        query.on("error_rate", series(0.5, "app", "z-gw", "pod", "p1"));
        engine.registerRule(errorRateRule("high-error-rate", "错误率过高"));

        engine.evaluate(1_000);
        assertThat(firingTitles()).hasSize(1);

        // 反向钉：pod 变了（无关标签），group_by 相同 ⇒ 不该多出一条
        query.on("error_rate", series(0.6, "app", "z-gw", "pod", "p2"));
        engine.evaluate(2_000);
        assertThat(firingTitles()).as("同组换 pod 不该多发通知").hasSize(1);

        // 真的换组了（app 变了）⇒ 才算另一条
        query.on("error_rate", series(0.5, "app", "z-order", "pod", "p1"));
        engine.evaluate(3_000);
        assertThat(firingTitles()).as("换组才多一条").hasSize(2);
        assertThat(firingTitles().get(1)).contains("z-order");
    }

    /**
     * inhibit：源告警存在时，目标规则的通知被压住。
     *
     * <p>反向钉：源告警不在同一范围（cluster 不同）时<b>不</b>压 ——
     * 凭空推断「属于同一范围」会把另一个集群的真故障一起吞掉。
     */
    @Test
    void inhibit_源告警存在时压住目标通知() {
        query.on("cluster_up", series(0, "cluster", "c1"));
        engine.registerRule(Rule.builder()
                .id("cluster-down").name("集群不可用")
                .expression(Expression.THRESHOLD).promql("cluster_up")
                .condition(Condition.EQ).threshold(0)
                .forDurationMillis(0).forHits(1)
                .groupBy("cluster")
                .build());
        engine.addInhibitRule(InhibitRule.sameLabelOn("i1",
                "cluster-down", "pod-error", "cluster"));

        engine.evaluate(1_000);
        assertThat(firingTitles()).hasSize(1);
        assertThat(firingTitles().get(0)).contains("集群不可用");

        // 集群整体挂了 ⇒ 每个 pod 的错误率都被顶起来（这正是要压掉的噪音）
        query.on("pod_error", series(0.9, "cluster", "c1", "app", "z-gw"));
        engine.registerRule(Rule.builder()
                .id("pod-error").name("实例错误率过高")
                .expression(Expression.THRESHOLD).promql("pod_error")
                .condition(Condition.GT).threshold(0.05)
                .forDurationMillis(0).forHits(1)
                .groupBy("cluster")
                .build());

        engine.evaluate(2_000);
        assertThat(firingTitles()).as("被抑制的目标不该发通知").hasSize(1);
        assertThat(firingTitles().get(0)).doesNotContain("实例错误率过高");

        // 反向钉：另一个集群的 pod 出问题 ⇒ 不该被压住。
        // 两个集群的实例同时在，c1 仍被压住、c2 真的发出去 ⇒ 压住的是「范围」不是「规则」。
        query.on("pod_error",
                series(0.9, "cluster", "c1", "app", "z-gw"),
                series(0.9, "cluster", "c2", "app", "z-order"));
        engine.evaluate(3_000);
        assertThat(firingTitles()).as("不同集群的实例错误率必须真的通知出去").hasSize(2);
        assertThat(firingTitles().get(1)).contains("z-order");
        assertThat(firingTitles().get(1)).doesNotContain("z-gw");
    }

    /**
     * silence：静默窗口内压住通知，<b>只记不发</b>；窗口外正常发。
     *
     * <p>反向钉：静默过期后同一类告警必须真的发出去 ——
     * 静默是把「这段时间别烦我」，不是「这段时间当作没这回事」。
     */
    @Test
    void silence_窗口内压住窗口外照发() {
        query.on("error_rate", series(0.5, "app", "z-gw"));
        engine.registerRule(errorRateRule("high-error-rate", "错误率过高"));
        engine.addSilence(new Silence("s1", Arrays.asList("app=z-gw"),
                0, 1_500, "计划内停机", "zifang"));

        engine.evaluate(1_000);
        assertThat(firingTitles()).as("静默窗口内不该外发").isEmpty();
        assertThat(dispatcher.silencedCount()).as("但必须留记录（事后能回答为什么没人收到）").isEqualTo(1);

        // 条件消失 ⇒ 恢复
        query.on("error_rate");
        engine.evaluate(2_000);

        // 静默过期后新发生的同类告警必须真的发出去
        query.on("error_rate", series(0.5, "app", "z-gw"));
        engine.evaluate(3_000);

        assertThat(firingTitles()).as("静默过期后必须外发").hasSize(1);
        assertThat(firingTitles().get(0)).contains("错误率过高");
    }
}
