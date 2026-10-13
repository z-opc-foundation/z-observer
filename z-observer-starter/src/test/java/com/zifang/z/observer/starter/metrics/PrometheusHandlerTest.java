package com.zifang.z.observer.starter.metrics;

import com.zifang.z.observer.core.metric.MetricRegistry;
import com.zifang.z.observer.core.model.Labels;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusHandlerTest {

    private static String body(MockHttpServletResponse res) throws IOException {
        return new String(res.getContentAsByteArray(), Charset.forName("UTF-8"));
    }

    @Test
    void 正常返回200与Prometheus内容类型() throws IOException {
        MetricRegistry r = new MetricRegistry(
                Labels.builder().put("app", "z-ops").build(), 100);
        r.counter("req_total", "请求数", Labels.empty()).inc();
        MockHttpServletResponse res = new MockHttpServletResponse();
        new PrometheusHandler(r).handle(null, res);
        assertEquals(200, res.getStatus());
        assertTrue(res.getContentType().startsWith("text/plain"));
        assertTrue(res.getContentType().contains("version=0.0.4"));
        assertTrue(body(res).contains("req_total{app=\"z-ops\"} 1"));
    }

    @Test
    void 响应体超限返回503而不是截断() throws IOException {
        MetricRegistry r = new MetricRegistry(Labels.empty(), 10_000);
        for (int i = 0; i < 50; i++) {
            Labels.builder().put("k" + i, "值".repeat(50));
            r.counter("m" + i, "带很长的 help 文本".repeat(30), Labels.empty()).inc();
        }
        PrometheusHandler handler = new PrometheusHandler(r, 100);
        MockHttpServletResponse res = new MockHttpServletResponse();
        handler.handle(null, res);
        assertEquals(503, res.getStatus(),
                "截断会让收端拿到半截指标，比明确报错危险");
        assertTrue(body(res).contains("降基数"));
        assertEquals(1, handler.oversizedScrapeCount());
    }

    @Test
    void 中文标签值不乱码() throws IOException {
        MetricRegistry r = new MetricRegistry(Labels.empty(), 100);
        // 值可以是 UTF-8，标签名不行（Prometheus 的标签名只允许 ASCII）
        r.counter("req", "", Labels.builder().put("api", "下单接口").build()).inc();
        MockHttpServletResponse res = new MockHttpServletResponse();
        new PrometheusHandler(r).handle(null, res);
        assertTrue(body(res).contains("api=\"下单接口\""), "实际输出: " + body(res));
    }

    @Test
    void 非ASCII标签名被拒() {
        MetricRegistry r = new MetricRegistry(Labels.empty(), 100);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> r.counter("req", "", Labels.builder().put("接口", "下单").build()));
    }
}