package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusTextWriterTest {

    private static String render(MetricRegistry r) {
        return new PrometheusTextWriter(r).render();
    }

    private static boolean hasLine(String text, String line) {
        for (String l : text.split("\n")) {
            if (l.equals(line)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void 输出HELP与TYPE行() {
        MetricRegistry r = new MetricRegistry();
        r.counter("http_requests_total", "收到的请求数", Labels.empty()).inc();
        String text = render(r);
        assertTrue(hasLine(text, "# HELP http_requests_total 收到的请求数"));
        assertTrue(hasLine(text, "# TYPE http_requests_total counter"));
        assertTrue(hasLine(text, "http_requests_total 1"));
    }

    @Test
    void 输出label并按名字排序() {
        MetricRegistry r = new MetricRegistry();
        r.counter("req", "", Labels.builder().put("code", "200").put("method", "GET").build()).inc();
        assertTrue(hasLine(render(r), "req{code=\"200\",method=\"GET\"} 1"));
    }

    @Test
    void 整数不带小数点() {
        MetricRegistry r = new MetricRegistry();
        Counter c = r.counter("req", "", Labels.empty());
        c.inc(3);
        assertTrue(hasLine(render(r), "req 3"));
        c.inc(0.5);
        assertTrue(hasLine(render(r), "req 3.5"));
    }

    @Test
    void 特殊值用Prometheus的字面量() {
        MetricRegistry r = new MetricRegistry();
        Gauge g = r.gauge("g", "", Labels.empty());
        g.set(Double.NaN);
        assertTrue(hasLine(render(r), "g NaN"));
        g.set(Double.POSITIVE_INFINITY);
        assertTrue(hasLine(render(r), "g +Inf"));
        g.set(Double.NEGATIVE_INFINITY);
        assertTrue(hasLine(render(r), "g -Inf"));
    }

    @Test
    void 直方图输出bucket_sum_count且带Inf桶() {
        MetricRegistry r = new MetricRegistry();
        Histogram h = r.histogram("latency", "", Labels.empty(), new double[]{1, 5});
        h.observe(0.5);
        h.observe(3);
        h.observe(100);
        String text = render(r);
        assertTrue(hasLine(text, "# TYPE latency histogram"));
        assertTrue(hasLine(text, "latency_bucket{le=\"1\"} 1"));
        assertTrue(hasLine(text, "latency_bucket{le=\"5\"} 2"));
        assertTrue(hasLine(text, "latency_bucket{le=\"+Inf\"} 1"));
        assertTrue(hasLine(text, "latency_count 3"));
        assertTrue(hasLine(text, "latency_sum 103.5"));
    }

    @Test
    void 直方图桶与原有标签共存() {
        MetricRegistry r = new MetricRegistry();
        Histogram h = r.histogram("latency", "", Labels.builder().put("api", "order").build(),
                new double[]{1});
        h.observe(0.2);
        assertTrue(hasLine(render(r), "latency_bucket{api=\"order\",le=\"1\"} 1"));
    }

    @Test
    void 标签值里的引号反斜杠换行被转义() {
        MetricRegistry r = new MetricRegistry();
        r.counter("m", "", Labels.builder().put("path", "a\"b\\c\nd").build()).inc();
        assertTrue(hasLine(render(r), "m{path=\"a\\\"b\\\\c\\nd\"} 1"));
    }

    @Test
    void 转义后的标签不会提前闭合() {
        // 未转义时这里的引号会让 "b" 变成标签名，产出一组无法解析的时序
        assertEquals("a\\\"b", PrometheusTextWriter.escapeLabelValue("a\"b"));
        assertEquals("a\\\\b", PrometheusTextWriter.escapeLabelValue("a\\b"));
        assertEquals("a\\nb", PrometheusTextWriter.escapeLabelValue("a\nb"));
        assertEquals("plain", PrometheusTextWriter.escapeLabelValue("plain"));
    }

    @Test
    void HELP里的换行与反斜杠被转义_否则渲染出的是坏的注释行() {
        assertEquals("line1\\nline2", PrometheusTextWriter.escapeHelp("line1\nline2"));
        assertEquals("c:\\\\path", PrometheusTextWriter.escapeHelp("c:\\path"));
    }

    @Test
    void 无标签指标不加大括号() {
        MetricRegistry r = new MetricRegistry();
        r.gauge("g", "", Labels.empty()).set(1);
        assertTrue(hasLine(render(r), "g 1"));
        assertFalse(render(r).contains("g{}"), "空标签不该渲染成 {}，老版本解析器不接受");
    }

    @Test
    void 观测系统自身的指标永远先出现() {
        MetricRegistry r = new MetricRegistry();
        r.counter("aaa_first", "", Labels.empty()).inc();
        String text = render(r);
        assertTrue(text.startsWith("# HELP z_observer_series_rejected_total"));
    }

    @Test
    void 输出按名字排序_让两次抓取可diff() {
        MetricRegistry r = new MetricRegistry();
        r.counter("z_metric", "", Labels.empty()).inc();
        r.counter("a_metric", "", Labels.empty()).inc();
        String text = render(r);
        assertTrue(text.indexOf("# TYPE a_metric") < text.indexOf("# TYPE z_metric"));
    }

    @Test
    void 公共标签出现在输出里() {
        MetricRegistry r = new MetricRegistry(
                Labels.builder().put("app", "z-ops").put("instance", "i-1").build(), 100);
        r.counter("req", "", Labels.empty()).inc();
        assertTrue(hasLine(render(r), "req{app=\"z-ops\",instance=\"i-1\"} 1"));
    }
}