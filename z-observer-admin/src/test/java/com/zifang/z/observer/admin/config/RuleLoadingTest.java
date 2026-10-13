package com.zifang.z.observer.admin.config;

import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.Series;
import com.zifang.z.observer.admin.notify.AlertDispatcher;
import com.zifang.z.observer.admin.notify.MessageChannel;
import com.zifang.z.observer.admin.rule.Condition;
import com.zifang.z.observer.admin.rule.Expression;
import com.zifang.z.observer.admin.rule.Rule;
import com.zifang.z.observer.admin.rule.RuleEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则装配与 absent 闭环。
 *
 * <p>这一组盯的是 M1 的验收项本身：配了规则，进程消失时通知真的发得出去。
 * 之前规则集恒空但整容器起得来、评估循环照跑、单测全绿 —— 告警通路是不存在的，
 * 而且一声不吭。
 */
class RuleLoadingTest {

    @SpringBootApplication
    @Import(ObserverAdminConfiguration.class)
    static class TestApp {
    }

    /** 收集式渠道：断言「通知发没发出去」而不是去看日志。 */
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

    /** 只按 PromQL 应答的查询面；promqlToSeries 里配什么就返什么。 */
    static final class StubQuery implements MetricQuery {
        private final Map<String, List<Series>> promqlToSeries =
                new LinkedHashMap<String, List<Series>>();
        private final Map<String, Long> lastSeen =
                new LinkedHashMap<String, Long>();

        StubQuery put(String promql, List<Series> series) {
            promqlToSeries.put(promql, series);
            return this;
        }

        /** 模拟「结果还在（5 分钟回看期内），但已经 N 毫秒没被采到」。 */
        StubQuery stale(String promql, long lastSeenMillis) {
            lastSeen.put(promql, Long.valueOf(lastSeenMillis));
            return this;
        }

        @Override
        public List<Series> instant(String promql, long timeMillis) {
            List<Series> s = promqlToSeries.get(promql);
            return s == null ? Collections.<Series>emptyList() : s;
        }

        @Override
        public List<Series> range(String promql, long from, long to, long step) {
            return instant(promql, from);
        }

        @Override
        public long lastSeenMillis(String promql) {
            Long v = lastSeen.get(promql);
            return v == null ? -1L : v.longValue();
        }
    }

    static RuleSpec absentSpec(String id, String promql, long forMillis) {
        return absentSpec(id, promql, forMillis, 30_000L);
    }

    static RuleSpec absentSpec(String id, String promql, long forMillis, long graceMillis) {
        RuleSpec spec = new RuleSpec();
        spec.setId(id);
        spec.setName("进程消失");
        spec.setSeverity("critical");
        spec.setExpression("ABSENT");
        spec.setPromql(promql);
        spec.setForDurationMillis(forMillis);
        spec.setAbsentGraceMillis(graceMillis);
        return spec;
    }

    @Test
    void 声明式配置能装出引擎认识的规则() {
        RuleSpec spec = absentSpec("process_absent", "up{job=\"z-gw\"}", 30000L);
        Rule rule = spec.toRule();

        assertThat(rule.expression()).isEqualTo(Expression.ABSENT);
        assertThat(rule.severity()).isEqualTo("critical");
        assertThat(rule.forDurationMillis()).isEqualTo(30000L);
        assertThat(rule.promql()).isEqualTo("up{job=\"z-gw\"}");
    }

    @Test
    void 阈值规则的条件与分组能装出来() {
        RuleSpec spec = new RuleSpec();
        spec.setId("error_rate");
        spec.setName("错误率高");
        spec.setPromql("rate(errors[5m])");
        spec.setCondition("GT");
        spec.setThreshold(0.01);
        spec.setGroupBy(java.util.Arrays.asList("job"));
        Rule rule = spec.toRule();

        assertThat(rule.condition()).isEqualTo(Condition.GT);
        assertThat(rule.threshold()).isEqualTo(0.01);
        assertThat(rule.groupBy()).containsExactly("job");
    }

    @Test
    void 组合布尔至少两个子条件才装得出来() {
        RuleSpec spec = new RuleSpec();
        spec.setId("both");
        spec.setExpression("BOOLEAN");
        RuleSpec.SubConditionSpec one = new RuleSpec.SubConditionSpec();
        one.setPromql("rate(errors[5m])");
        one.setThreshold(0.01);
        spec.setConditions(java.util.Arrays.asList(one));
        try {
            spec.toRule();
            assertThat(false).as("单个子条件的 BOOLEAN 规则应当被拒").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("至少要两个子条件");
        }
    }

    @Test
    void 查不到即告警_条件成立够久才发通知() {
        RecordingChannel channel = new RecordingChannel();
        StubQuery query = new StubQuery();
        // up{job="z-gw"} 查不到 —— 进程彻底消失后 Prometheus 会因 staleness 删掉时序
        Map<String, List<MessageChannel>> channels = new LinkedHashMap<String, List<MessageChannel>>();
        channels.put("*", Collections.singletonList((MessageChannel) channel));
        AlertDispatcher dispatcher = new AlertDispatcher(channels);
        RuleEngine engine = new RuleEngine(query, dispatcher);
        engine.registerRule(absentSpec("gw_down", "up{job=\"z-gw\"}", 30000L).toRule());

        long t0 = 1_000_000L;
        // 第一轮：条件成立但确认时长没到 —— 只进 PENDING，不发通知
        assertThat(engine.evaluate(t0)).isZero();
        assertThat(engine.activeAlerts()).hasSize(1);
        assertThat(channel.titles).isEmpty();

        // 确认时长未满的下一轮：仍然不发
        assertThat(engine.evaluate(t0 + 15_000L)).isZero();
        assertThat(channel.titles).isEmpty();

        // 确认时长满：进 FIRING，通知真的发出去
        assertThat(engine.evaluate(t0 + 30_000L)).isEqualTo(1);
        assertThat(channel.titles).hasSize(1);
        assertThat(channel.titles.get(0)).contains("进程消失");
        assertThat(dispatcher.sentCount()).isEqualTo(1);
    }

    @Test
    void 时序回来了发恢复通知() {
        RecordingChannel channel = new RecordingChannel();
        StubQuery query = new StubQuery();
        Map<String, List<MessageChannel>> channels = new LinkedHashMap<String, List<MessageChannel>>();
        channels.put("*", Collections.singletonList((MessageChannel) channel));
        AlertDispatcher dispatcher = new AlertDispatcher(channels);
        RuleEngine engine = new RuleEngine(query, dispatcher);
        engine.registerRule(absentSpec("gw_down", "up{job=\"z-gw\"}", 0L).toRule());

        long t0 = 1_000_000L;
        // 确认时长 0：第一轮就进 FIRING 并通知
        assertThat(engine.evaluate(t0)).isEqualTo(1);
        assertThat(channel.titles).hasSize(1);

        // 时序回来：条件不再成立，走 RESOLVED 并发恢复通知。
        // 不做这一步的话「恢复通知跟随」这个验收项永远过不了。
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("job", "z-gw");
        List<Series> back = new ArrayList<Series>();
        back.add(new Series(labels, 1.0d, t0));
        query.put("up{job=\"z-gw\"}", back);

        assertThat(engine.evaluate(t0 + 15_000L)).isEqualTo(1);
        assertThat(channel.titles).hasSize(2);
        assertThat(engine.activeAlerts()).isEmpty();
    }

    @Test
    void 查询结果还在但已过期_照样判消失() {
        RecordingChannel channel = new RecordingChannel();
        StubQuery query = new StubQuery();
        // Prometheus 的 instant 查询有 5 分钟回看：进程刚挂时结果里仍有最后一个点。
        // 只认「结果为空」的话，M1 的 60 秒口径会退化成 5 分钟。
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("job", "z-gw");
        List<Series> stillThere = new ArrayList<Series>();
        stillThere.add(new Series(labels, 0.0d, 1_000_000L));
        query.put("up{job=\"z-gw\"}", stillThere);
        query.stale("up{job=\"z-gw\"}", 1_000_000L);

        Map<String, List<MessageChannel>> channels = new LinkedHashMap<String, List<MessageChannel>>();
        channels.put("*", Collections.singletonList((MessageChannel) channel));
        AlertDispatcher dispatcher = new AlertDispatcher(channels);
        RuleEngine engine = new RuleEngine(query, dispatcher);
        engine.registerRule(absentSpec("gw_down", "up{job=\"z-gw\"}", 0L).toRule());

        // 刚挂：lastSeen 就在 20ms 前，还在宽限内，不告警
        assertThat(engine.evaluate(1_000_020L)).isZero();
        assertThat(channel.titles).isEmpty();

        // 超过宽限：判消失并发通知
        assertThat(engine.evaluate(1_060_000L)).isEqualTo(1);
        assertThat(channel.titles).hasSize(1);
        // 时间戳进 annotations 不进 labels：labels 参与 fingerprint，不能每轮变
        assertThat(engine.activeAlerts().get(0).annotations())
                .containsEntry("lastSeenMillis", "1000000");
        assertThat(engine.activeAlerts().get(0).labels())
                .doesNotContainKey("lastSeenMillis");
    }

    /**
     * 每轮都在变的东西不能进 labels。
     *
     * <p>labels 参与 fingerprint，而 fingerprint 是状态机的键。absent 的
     * lastSeenMillis 每轮都不同，一旦进了 labels，每轮都被当成一条<b>新</b>告警，
     * PENDING 每次从头开始 —— 确认时长永远攒不满，告警一次都发不出去。
     * 这个 bug 在真跑起来之前看不出来：状态确实是 PENDING，看起来「在正常工作」。
     */
    @Test
    void 结果每轮在变也不能被当成新告警() {
        RecordingChannel channel = new RecordingChannel();
        StubQuery query = new StubQuery();
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("job", "z-gw");
        List<Series> stillThere = new ArrayList<Series>();
        stillThere.add(new Series(labels, 0.0d, 1_000_000L));

        Map<String, List<MessageChannel>> channels = new LinkedHashMap<String, List<MessageChannel>>();
        channels.put("*", Collections.singletonList((MessageChannel) channel));
        AlertDispatcher dispatcher = new AlertDispatcher(channels);
        RuleEngine engine = new RuleEngine(query, dispatcher);
        engine.registerRule(absentSpec("gw_down", "up{job=\"z-gw\"}", 20_000L, 10_000L).toRule());

        // 三轮，lastSeen 每次都往前挪（模拟时间在走）
        query.put("up{job=\"z-gw\"}", stillThere);
        query.stale("up{job=\"z-gw\"}", 1_000_000L);
        assertThat(engine.evaluate(1_030_000L)).isZero();
        query.stale("up{job=\"z-gw\"}", 1_030_000L);
        assertThat(engine.evaluate(1_045_000L)).isZero();

        // 始终只有一条活跃告警：没被当成三条新的
        assertThat(engine.activeAlerts()).hasSize(1);
        assertThat(engine.activeAlerts().get(0).state().name()).isEqualTo("PENDING");
        assertThat(engine.activeAlerts().get(0).activeSinceMillis()).isEqualTo(1_030_000L);

        // 确认时长从「第一次进 PENDING」起算，不是每轮重置 —— 到点必发
        assertThat(engine.evaluate(1_050_000L)).isEqualTo(1);
        assertThat(channel.titles).hasSize(1);
    }

    @Test
    void M1验收的最坏耗时在60秒内() {
        // 评估间隔 15s + 确认时长 30s：进程消失后最坏两轮半进 FIRING。
        // 这个断言的意义是把「60 秒」钉成一个会被跑坏的数 ——
        // 有人调小评估间隔或确认时长时它会红，而不是让 M1 悄悄退化成「几分钟内」。
        long evaluateIntervalMillis = 15_000L;
        long forDurationMillis = 30_000L;
        long worstCase = forDurationMillis + evaluateIntervalMillis;
        assertThat(worstCase).isLessThan(60_000L);
    }

    /**
     * 真起容器时，默认 application.yml 里的规则必须真的装进引擎。
     *
     * <p>这条用的是 admin 模块的默认配置（不覆写 z.observer.rules），
     * 所以「yml 里写了规则」和「引擎里有规则」必须是同一件事，
     * 否则就退回成上一轮那个一声不吭的空转。
     */
    @SpringBootTest(classes = RuleLoadingTest.ContainerTest.class, properties = {
            "z.observer.collector-port=0",
            "z.observer.prometheus-url=http://127.0.0.1:1",
            "z.observer.scrape-interval-millis=3600000",
            "z.observer.evaluate-interval-millis=3600000",
            "z.observer.scrape-urls=http://127.0.0.1:1/metrics"
    })
    static class ContainerTest {

        @Autowired
        private RuleEngine engine;

        @Test
        void 默认配置里的规则真的装进了引擎() {
            List<Rule> rules = engine.rules();
            assertThat(rules).isNotEmpty();
            assertThat(rules).anyMatch(new java.util.function.Predicate<Rule>() {
                @Override
                public boolean test(Rule r) {
                    return "process_absent".equals(r.id());
                }
            });
            for (Rule r : rules) {
                // M1 的 60 秒口径对每一条规则都成立，不只是写进注释的那条
                assertThat(r.forDurationMillis())
                        .as("规则 " + r.id() + " 的确认时长 + 评估间隔必须在 60 秒内")
                        .isLessThanOrEqualTo(45_000L);
            }
        }
    }
}
