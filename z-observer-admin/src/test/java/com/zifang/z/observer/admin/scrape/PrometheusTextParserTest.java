package com.zifang.z.observer.admin.scrape;

import com.zifang.z.observer.core.metric.PrometheusTextParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * exposition 解析（text format 0.0.4）的行为口径。
 *
 * <p>放在 admin 的测试树里而不是 core 的：解析器在 core，<b>但它服务的是采集层</b>
 * （收端抓回来的东西），而这一层的验收口径是采集侧的（解析零失败 / 坏行要能说清），
 * 放在采集层旁边比放在 core 里更容易被后来的人看到。
 */
class PrometheusTextParserTest {

    private final PrometheusTextParser parser = new PrometheusTextParser();

    @Test
    void 计数与标签都能读出来() {
        String text = ""
                + "# HELP z_app_requests_total 应用请求数\n"
                + "# TYPE z_app_requests_total counter\n"
                + "z_app_requests_total{app=\"z-gw\",code=\"200\"} 42\n"
                + "z_app_requests_total{app=\"z-gw\",code=\"500\"} 3\n";

        PrometheusTextParser.ParseResult r = parser.parse(text);

        assertThat(r.hasErrors()).isFalse();
        assertThat(r.sampleCount()).isEqualTo(2);
        PrometheusTextParser.ParsedSample first = r.samples().get(0);
        assertThat(first.name()).isEqualTo("z_app_requests_total");
        assertThat(first.labels()).containsEntry("app", "z-gw").containsEntry("code", "200");
        assertThat(first.value()).isEqualTo(42d);
        assertThat(first.type()).isEqualTo("counter");
        assertThat(first.help()).isEqualTo("应用请求数");
    }

    @Test
    void 直方图的桶与汇总都能读出来() {
        String text = ""
                + "# TYPE z_app_latency_seconds histogram\n"
                + "z_app_latency_seconds_bucket{app=\"z-gw\",le=\"0.1\"} 3\n"
                + "z_app_latency_seconds_bucket{app=\"z-gw\",le=\"+Inf\"} 5\n"
                + "z_app_latency_seconds_sum{app=\"z-gw\"} 1.5\n"
                + "z_app_latency_seconds_count{app=\"z-gw\"} 5\n";

        PrometheusTextParser.ParseResult r = parser.parse(text);

        assertThat(r.hasErrors()).isFalse();
        assertThat(r.sampleCount()).isEqualTo(4);
        List<PrometheusTextParser.ParsedSample> samples = r.samples();
        assertThat(samples.get(0).labels()).containsEntry("le", "0.1");
        assertThat(samples.get(1).labels()).containsEntry("le", "+Inf");
        assertThat(samples.get(3).value()).isEqualTo(5d);
    }

    @Test
    void 标签值里的转义与中文能原样读回来() {
        String text = "z_app_info{app=\"订单\\\"服务\",path=\"C:\\\\tmp\"} 1\n";

        PrometheusTextParser.ParseResult r = parser.parse(text);

        assertThat(r.hasErrors()).isFalse();
        assertThat(r.samples().get(0).labels())
                .containsEntry("app", "订单\"服务")
                .containsEntry("path", "C:\\tmp");
    }

    @Test
    void 特殊取值按Prometheus口径读() {
        String text = "a_nan NaN\na_pos +Inf\na_neg -Inf\n";

        PrometheusTextParser.ParseResult r = parser.parse(text);

        assertThat(r.hasErrors()).isFalse();
        assertThat(r.samples().get(0).value()).isNaN();
        assertThat(r.samples().get(1).value()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(r.samples().get(2).value()).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void 可选的时间戳能读出来_没写就是null() {
        PrometheusTextParser.ParseResult r = parser.parse("a 1 1700000000000\nb 2\n");

        assertThat(r.samples().get(0).timestampMillis()).isEqualTo(1700000000000L);
        assertThat(r.samples().get(1).timestampMillis()).isNull();
    }

    @Test
    void 坏行只记错不抛异常_好数据照收() {
        String text = ""
                + "good_one 1\n"
                + "bad_line_without_value\n"
                + "good_two{unterminated=\"x 2\n"
                + "good_three 3\n";

        PrometheusTextParser.ParseResult r = parser.parse(text);

        assertThat(r.sampleCount()).isEqualTo(2);
        assertThat(r.errorCount()).isEqualTo(2);
        assertThat(r.errors().get(0).lineNumber()).isEqualTo(2);
        assertThat(r.errors().get(0).reason()).contains("取值");
        assertThat(r.errors().get(1).lineNumber()).isEqualTo(3);
    }

    @Test
    void 注释与空行不计入样本() {
        PrometheusTextParser.ParseResult r = parser.parse(
                "# HELP only_help 说明\n\n# 任意注释\n# TYPE only_type counter\nonly 1\n");

        assertThat(r.sampleCount()).isEqualTo(1);
        assertThat(r.hasErrors()).isFalse();
    }

    @Test
    void 空输入不炸() {
        assertThat(parser.parse(null).sampleCount()).isZero();
        assertThat(parser.parse("").sampleCount()).isZero();
    }
}
