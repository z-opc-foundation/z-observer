package com.zifang.z.observer.admin.scrape;

import com.zifang.z.observer.core.metric.PrometheusTextParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集状态登记处（<b>absent 数据源</b>）的行为口径。
 */
class ScrapeStatusRegistryTest {

    @Test
    void 抓到的target算健康_抓不到的算缺席() {
        ScrapeStatusRegistry reg = new ScrapeStatusRegistry();
        reg.record(ScrapeEvent.succeeded("a", 1000L, 10));
        reg.record(ScrapeEvent.failed("b", 1000L, "Connection refused"));

        assertThat(reg.status("a").isUp()).isTrue();
        assertThat(reg.status("a").isAbsent()).isFalse();
        assertThat(reg.status("b").isUp()).isFalse();
        assertThat(reg.status("b").isAbsent()).isTrue();
        assertThat(reg.absentTargets()).containsExactly("b");
    }

    @Test
    void 曾经抓得到现在抓不到_必须算缺席() {
        ScrapeStatusRegistry reg = new ScrapeStatusRegistry();
        reg.record(ScrapeEvent.succeeded("a", 1000L, 10));
        reg.record(ScrapeEvent.failed("a", 2000L, "Connection refused"));

        assertThat(reg.status("a").isAbsent()).isTrue();
        assertThat(reg.status("a").consecutiveFailures()).isEqualTo(1);
        assertThat(reg.status("a").lastSuccessMillis()).isEqualTo(1000L);
        assertThat(reg.status("a").lastFailureMillis()).isEqualTo(2000L);
    }

    @Test
    void 又抓到了就销账_缺席名单清空() {
        ScrapeStatusRegistry reg = new ScrapeStatusRegistry();
        reg.record(ScrapeEvent.failed("a", 1000L, "Connection refused"));
        reg.record(ScrapeEvent.succeeded("a", 2000L, 7));

        assertThat(reg.absentTargets()).isEmpty();
        assertThat(reg.status("a").consecutiveFailures()).isZero();
    }

    @Test
    void 解析失败按失败论_不算健康() {
        ScrapeStatusRegistry reg = new ScrapeStatusRegistry();
        reg.record(ScrapeEvent.parseFailed("a", 1000L, "第 3 行解析失败"));

        assertThat(reg.status("a").isAbsent()).isTrue();
        assertThat(reg.parseFailureCount()).isEqualTo(1);
        assertThat(reg.failureCount()).isZero();
    }

    @Test
    void 事件窗口有上限_超了丢最老的() {
        ScrapeStatusRegistry reg = new ScrapeStatusRegistry(3);
        for (int i = 1; i <= 5; i++) {
            reg.record(ScrapeEvent.succeeded("t" + i, i, 1));
        }

        assertThat(reg.events()).hasSize(3);
        assertThat(reg.events().get(0).targetId()).isEqualTo("t3");
    }

    @Test
    void 缺席状态能渲染成exposition_供absent规则消费() {
        ScrapeStatusRegistry reg = new ScrapeStatusRegistry();
        reg.record(ScrapeEvent.succeeded("z-gw@10.0.0.1:9100", 1000L, 3));
        reg.record(ScrapeEvent.failed("z-gw@10.0.0.2:9100", 1000L, "Connection refused"));

        String text = reg.toExposition();

        assertThat(text).contains("# TYPE z_observer_scrape_target_absent gauge");
        assertThat(text).contains("z_observer_scrape_target_absent{target=\"z-gw@10.0.0.2:9100\"} 1");
        assertThat(text).contains("z_observer_scrape_target_absent{target=\"z-gw@10.0.0.1:9100\"} 0");
        assertThat(text).contains("z_observer_scrape_target_up{target=\"z-gw@10.0.0.1:9100\"} 1");
    }

    @Test
    void 渲染出来的exposition能被自己的解析器读回去() {
        ScrapeStatusRegistry reg = new ScrapeStatusRegistry();
        reg.record(ScrapeEvent.succeeded("a", 1000L, 3));
        reg.record(ScrapeEvent.failed("b", 1000L, "boom"));

        PrometheusTextParser.ParseResult parsed = new PrometheusTextParser().parse(reg.toExposition());

        assertThat(parsed.hasErrors()).isFalse();
        List<PrometheusTextParser.ParsedSample> absent = parsed.samples().stream()
                .filter(s -> "z_observer_scrape_target_absent".equals(s.name()))
                .collect(java.util.stream.Collectors.toList());
        assertThat(absent).hasSize(2);
        assertThat(absent.stream()
                .filter(s -> "b".equals(s.labels().get("target")))
                .findFirst().get().value()).isEqualTo(1d);
    }

    @Test
    void 状态快照不被外部改动影响() {
        ScrapeStatusRegistry reg = new ScrapeStatusRegistry();
        reg.record(ScrapeEvent.succeeded("a", 1000L, 3));
        List<ScrapeStatus> snapshot = reg.snapshot();
        reg.record(ScrapeEvent.failed("a", 2000L, "boom"));

        assertThat(snapshot.get(0).isUp()).isTrue();
        assertThat(reg.snapshot().get(0).isUp()).isFalse();
    }

    @Test
    void 事件必须带target与类型() {
        List<ScrapeEvent> events = Arrays.asList(
                ScrapeEvent.succeeded("a", 1L, 1),
                ScrapeEvent.failed("a", 2L, "x"),
                ScrapeEvent.vanished("a", 3L),
                ScrapeEvent.reappeared("a", 4L));

        assertThat(events.get(0).isUnreachable()).isFalse();
        assertThat(events.get(1).isUnreachable()).isTrue();
        assertThat(events.get(2).isUnreachable()).isTrue();
        assertThat(events.get(3).isUnreachable()).isFalse();
    }
}
