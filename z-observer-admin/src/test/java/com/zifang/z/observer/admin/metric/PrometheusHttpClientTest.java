package com.zifang.z.observer.admin.metric;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrometheusHttpClientTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HttpServer server;
    private PrometheusHttpClient client;
    private final AtomicReference<String> lastQuery = new AtomicReference<String>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        client = new PrometheusHttpClient("http://127.0.0.1:" + server.getAddress().getPort(), 2000);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(final String body) {
        respond(200, body);
    }

    private void respond(final int code, final String body) {
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws java.io.IOException {
                lastQuery.set(exchange.getRequestURI().getRawQuery());
                byte[] bytes = body.getBytes(UTF8);
                exchange.sendResponseHeaders(code, bytes.length);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes);
                out.close();
            }
        });
        server.start();
    }

    @Test
    void 即时查询能解析出标签和值() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"app\":\"z-gw\",\"instance\":\"10.0.0.1:8080\"},"
                + "\"value\":[1700000000,\"0.85\"]}]}}");

        List<Series> series = client.instant("up", 1700000000000L);

        assertThat(series).hasSize(1);
        assertThat(series.get(0).value()).isEqualTo(0.85);
        assertThat(series.get(0).label("app")).isEqualTo("z-gw");
        assertThat(series.get(0).timestampMillis()).isEqualTo(1700000000000L);
    }

    @Test
    void 区间查询摊平成按时序分组前的序列() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"app\":\"z-gw\"},\"values\":["
                + "[1700000000,\"1\"],[1700000060,\"2\"],[1700000120,\"3\"]]}]}}");

        List<Series> series = client.range("up", 1700000000000L, 1700000120000L, 60000);

        assertThat(series).hasSize(3);
        assertThat(series.get(0).value()).isEqualTo(1.0);
        assertThat(series.get(2).value()).isEqualTo(3.0);
    }

    @Test
    void 没有匹配的时序返回空列表而不是抛异常() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
        assertThat(client.instant("up", 0)).isEmpty();
    }

    @Test
    void NaN与Inf如实解析不被当成0() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"a\":\"1\"},\"value\":[1700000000,\"NaN\"]},"
                + "{\"metric\":{\"a\":\"2\"},\"value\":[1700000000,\"+Inf\"]}]}}");

        List<Series> series = client.instant("x", 0);

        assertThat(series.get(0).value()).isNaN();
        assertThat(series.get(1).value()).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void PromQL语法错必须抛异常而不是当成空结果() {
        // 这是本类最重要的一条：返回空列表会被规则引擎读成「指标不存在」，
        // 于是一次表达式写错就变成一批实例的 absent 告警。
        respond(400, "{\"status\":\"error\",\"errorType\":\"bad_data\","
                + "\"error\":\"parse error at char 3\"}");

        assertThatThrownBy(() -> client.instant("up{", 0))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("parse error");
    }

    @Test
    void 响应不是JSON时抛异常() {
        respond("<html>502 Bad Gateway</html>");
        assertThatThrownBy(() -> client.instant("up", 0))
                .isInstanceOf(PrometheusQueryException.class);
    }

    @Test
    void PromQL里的特殊字符被正确编码() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
        client.instant("sum(rate(http_requests_total{job=\"a b\",code=~\"5..\"}[5m]))", 0);
        assertThat(lastQuery.get()).contains("job%3D%22a+b%22");
    }

    @Test
    void lastSeen取区间内最新的采样时间() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"app\":\"a\"},\"values\":[[1700000000,\"1\"],[1700000120,\"1\"]]},"
                + "{\"metric\":{\"app\":\"b\"},\"values\":[[1700000060,\"1\"]]}]}}");

        assertThat(client.lastSeenMillis("up")).isEqualTo(1700000120000L);
    }

    @Test
    void 区间参数颠倒直接报错() {
        assertThatThrownBy(() -> client.range("up", 2000, 1000, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 时序标识与标签插入顺序无关() {
        Series a = new Series(new java.util.LinkedHashMap<String, String>() {{
            put("app", "z-gw");
            put("instance", "i-1");
        }}, 1, 0);
        Series b = new Series(new java.util.LinkedHashMap<String, String>() {{
            put("instance", "i-1");
            put("app", "z-gw");
        }}, 1, 0);

        // 插入序不一致是常态（不同查询、不同实现）。用它做 key 的话同一个时序
        // 会分裂成两条，告警分组就散了。
        assertThat(a.seriesKey()).isEqualTo(b.seriesKey());
    }
}
