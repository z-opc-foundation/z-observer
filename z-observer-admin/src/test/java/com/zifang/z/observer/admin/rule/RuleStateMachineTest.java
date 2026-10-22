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

/**
 * 两段确认状态机的全路径覆盖。
 *
 * <p>本卡第 4 项要的就是这条链：
 * <pre>
 *   OK → PENDING → FIRING → RESOLVED
 *        └────→ OK（中途回退）
 * </pre>
 *
 * <p><b>两把闸门缺一不可</b>：时长闸门（{@code forDurationMillis}）与计数闸门
 * （{@code forHits}，连续命中 N 个评估周期）。本类把「计数闸门是承重墙」这件事
 * 钉死：所有抖动用例都把<b>时长闸门放到 0</b> —— 此时只剩计数闸门能挡住，
 * 谁把计数闸门删了/改成 1，这些用例立刻变红。
 *
 * <p>另外钉住一条容易漏的：<b>中途回 OK 时确认计数必须归零</b>。
 * 不归零的话「命中-未命中-命中-命中」会被算成连续命中 3 次 ⇒ 提前发通知，
 * 而真实场景里那次未命中恰恰说明问题已经缓解。
 */
class RuleStateMachineTest {

    /** 可编排的假查询面：按 promql 配返回结果。 */
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

    /** 记录通知事件。 */
    private static final class RecordingNotifier implements AlertNotifier {
        final List<String> events = new ArrayList<String>();

        @Override
        public void notifyFiring(Alert alert, Silence silencedBy) {
            events.add("FIRING " + alert.ruleId());
        }

        @Override
        public void notifyResolved(Alert alert) {
            events.add("RESOLVED " + alert.ruleId());
        }
    }

    private static Series series(String app, double value) {
        Map<String, String> labels = new HashMap<String, String>();
        labels.put("app", app);
        return new Series(labels, value, 0);
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

    /**
     * 一条阈值规则：{@code error_rate > 0.05}。
     *
     * @param forMillis 时长闸门（毫秒）
     * @param forHits   计数闸门（连续命中几个周期）
     */
    private Rule thresholdRule(long forMillis, int forHits) {
        return Rule.builder()
                .id("high-error-rate").name("错误率过高").severity("critical")
                .expression(Expression.THRESHOLD)
                .promql("error_rate")
                .condition(Condition.GT).threshold(0.05)
                .forDurationMillis(forMillis)
                .forHits(forHits)
                .groupBy("app")
                .build();
    }

    /** 取某条规则的状态变迁轨迹。 */
    private List<AlertTransition> trailOf(String ruleId) {
        List<AlertTransition> out = new ArrayList<AlertTransition>();
        for (AlertTransition t : engine.transitions()) {
            if (t.ruleId().equals(ruleId)) {
                out.add(t);
            }
        }
        return out;
    }

    /** 轨迹里出现的状态序列（跳过 PENDING→PENDING 这种原地踏步）。 */
    private List<String> statePath(String ruleId) {
        List<String> path = new ArrayList<String>();
        for (AlertTransition t : trailOf(ruleId)) {
            if (t.from() == t.to()) {
                continue;
            }
            String step = t.from() + "->" + t.to();
            if (path.isEmpty() || !path.get(path.size() - 1).equals(step)) {
                path.add(step);
            }
        }
        return path;
    }

    // ---- 全路径：OK → PENDING → FIRING → RESOLVED ----

    @Test
    void 全路径_OK到PENDING到FIRING到RESOLVED() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0, 2));

        // 第一轮：条件成立但未确认 ⇒ OK → PENDING
        engine.evaluate(1_000);
        assertThat(engine.activeAlerts()).hasSize(1);
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.PENDING);
        assertThat(notifier.events).isEmpty();

        // 第二轮：两把闸门都过 ⇒ PENDING → FIRING，发告警通知
        engine.evaluate(2_000);
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.FIRING);
        assertThat(notifier.events).containsExactly("FIRING high-error-rate");

        // 第三轮：条件消失 ⇒ FIRING → RESOLVED，发恢复通知
        query.on("error_rate");
        engine.evaluate(3_000);
        assertThat(engine.activeAlerts()).isEmpty();
        assertThat(notifier.events).containsExactly("FIRING high-error-rate", "RESOLVED high-error-rate");

        // 轨迹能完整回放这条链
        assertThat(statePath("high-error-rate")).containsExactly(
                "OK->PENDING", "PENDING->FIRING", "FIRING->RESOLVED");
    }

    @Test
    void 中途回OK_PENDING直接退回OK且不发任何通知() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0, 3));

        engine.evaluate(1_000);   // 命中 ⇒ PENDING（确认计数 1）
        engine.evaluate(2_000);   // 又命中 ⇒ 确认计数 2，仍未满 3

        // 条件消失：待确认作废 ⇒ PENDING → OK
        query.on("error_rate");
        engine.evaluate(3_000);

        assertThat(notifier.events).isEmpty();
        assertThat(engine.activeAlerts()).isEmpty();
        assertThat(statePath("high-error-rate")).containsExactly("OK->PENDING", "PENDING->OK");
    }

    /**
     * 中途回 OK 时<b>确认计数必须归零</b>。
     *
     * <p>不归零的话「命中-未命中-命中-命中」会被算成「连续命中 3 次」⇒ 提前发通知。
     * 那次未命中恰恰说明问题已经缓解了，不该被算进连续命中里。
     */
    @Test
    void 中途回OK后确认计数归零_要重新攒满N次() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0, 3));

        engine.evaluate(1_000);   // 命中 1
        query.on("error_rate");   // 未命中
        engine.evaluate(2_000);   // ⇒ 计数归零，记录作废

        query.on("error_rate", series("z-gw", 0.5));
        engine.evaluate(3_000);   // 命中 ⇒ 计数重新从 1 开始
        engine.evaluate(4_000);   // 命中 ⇒ 计数 2，仍未满 3
        assertThat(notifier.events).as("计数没归零的话这里就提前发了").isEmpty();
        assertThat(engine.activeAlerts().get(0).hitCount()).isEqualTo(2);

        engine.evaluate(5_000);   // 命中 ⇒ 计数 3 ⇒ 才发
        assertThat(notifier.events).containsExactly("FIRING high-error-rate");
    }

    // ---- 计数闸门（两段确认的承重墙）----

    /**
     * 抖动场景：命中-未命中交替，<b>永不</b>进 FIRING。
     *
     * <p>时长闸门在这里被设成 <b>0</b> —— 也就是说「持续够久」这道门是敞开的，
     * 唯一能挡住它的就是<b>连续命中 N 次</b>这道计数闸门。
     * ⇒ 谁把计数闸门删掉、或悄悄改成默认的 1，这个用例立刻变红。
     */
    @Test
    void 抖动场景_命中未命中交替_永不FIRING() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0, 3));

        long t = 1_000;
        for (int i = 0; i < 20; i++) {
            if (i % 2 == 0) {
                query.on("error_rate", series("z-gw", 0.5));   // 命中
            } else {
                query.on("error_rate");                        // 未命中
            }
            engine.evaluate(t);
            t += 15_000;
        }

        assertThat(notifier.events).as("抖动绝不能发通知").isEmpty();
        assertThat(engine.activeAlerts()).as("最后一轮未命中 ⇒ 没有悬挂的 pending").isEmpty();

        // 轨迹里一次 FIRING 都没有
        for (AlertTransition tr : trailOf("high-error-rate")) {
            assertThat(tr.to()).isNotEqualTo(AlertState.FIRING);
        }
    }

    @Test
    void 连续命中满N次才进FIRING() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0, 3));

        engine.evaluate(1_000);   // 计数 1
        engine.evaluate(2_000);   // 计数 2
        assertThat(notifier.events).as("只命中 2 次不该发").isEmpty();
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.PENDING);

        engine.evaluate(3_000);   // 计数 3 ⇒ 满
        assertThat(notifier.events).containsExactly("FIRING high-error-rate");
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.FIRING);
        assertThat(engine.activeAlerts().get(0).hitCount()).isEqualTo(3);
    }

    /**
     * 单次命中必须停在 PENDING。
     *
     * <p>这是计数闸门最直接的判据：N=3 时第一次命中绝不能发通知。
     */
    @Test
    void 单次命中必须停在PENDING() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0, 3));

        engine.evaluate(1_000);

        assertThat(notifier.events).isEmpty();
        assertThat(engine.activeAlerts()).hasSize(1);
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.PENDING);
        assertThat(engine.activeAlerts().get(0).hitCount()).isEqualTo(1);
    }

    /**
     * 两把闸门是<b>与</b>关系：计数满了但时长没到，照样不发。
     */
    @Test
    void 计数满了但时长没到_照样不发() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(60_000, 2));

        engine.evaluate(1_000);   // 计数 1
        engine.evaluate(2_000);   // 计数 2（计数闸门已满），但只持续了 1s
        assertThat(notifier.events).as("时长闸门没过 ⇒ 不发").isEmpty();
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.PENDING);

        engine.evaluate(61_000);  // 持续 60s ⇒ 时长闸门也过 ⇒ 发
        assertThat(notifier.events).containsExactly("FIRING high-error-rate");
    }

    /**
     * 时长满了但计数没满，也不发。
     *
     * <p>时长闸门放到 0 时这一条没法测（时长立刻就满），所以这里用一个
     * 「计数比时长更紧」的配置：forHits=5、forDuration=1000 —— 评估间隔 1s 时
     * 时长早就满了，但连续命中要攒到 5 次才放行。
     */
    @Test
    void 时长满了但计数没满_照样不发() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(1_000, 5));

        engine.evaluate(1_000);
        engine.evaluate(2_000);
        engine.evaluate(3_000);
        engine.evaluate(4_000);
        assertThat(notifier.events).as("连续命中 4 次没到 5 ⇒ 不发").isEmpty();
        assertThat(engine.activeAlerts().get(0).state()).isEqualTo(AlertState.PENDING);

        engine.evaluate(5_000);
        assertThat(notifier.events).containsExactly("FIRING high-error-rate");
    }

    /** 计数闸门为 1 时不额外约束（老规则的默认行为不变）。 */
    @Test
    void 计数闸门为1时不额外约束() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0, 1));

        engine.evaluate(1_000);

        assertThat(notifier.events).containsExactly("FIRING high-error-rate");
    }

    /** 计数闸门小于 1 是配置错误，必须当场拒。 */
    @Test
    void 计数闸门小于1直接报错() {
        try {
            Rule.builder().id("x").promql("error_rate").forHits(0).build();
            org.junit.jupiter.api.Assertions.fail("应当拒绝小于 1 的确认次数");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("确认次数不能小于 1");
        }
    }

    // ---- 轨迹 ----

    @Test
    void 状态轨迹能回放一次告警的完整判定链() {
        query.on("error_rate", series("z-gw", 0.5));
        engine.registerRule(thresholdRule(0, 3));

        engine.evaluate(1_000);
        engine.evaluate(2_000);
        engine.evaluate(3_000);

        List<AlertTransition> trail = trailOf("high-error-rate");
        assertThat(trail).isNotEmpty();
        // 每一步都带确认次数 ⇒ 能回答「第几轮才攒满」
        assertThat(trail.get(trail.size() - 1).to()).isEqualTo(AlertState.FIRING);
        assertThat(trail.get(trail.size() - 1).hitCount()).isEqualTo(3);
        assertThat(trail.get(trail.size() - 1).reason()).contains("连续命中");
        // 每一步都带时间戳 ⇒ 能回答「什么时候」
        assertThat(trail.get(trail.size() - 1).atMillis()).isEqualTo(3_000);
    }
}
