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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标量查询语义（PromQL 的第三种结果形态）。
 *
 * <p>为什么单独钉：Prometheus 的标量结果是 {@code [ts, "值"]} 这个<b>二元组</b>，
 * 不是时序数组。当成时序数组解析会静默得到空列表 ——
 * 那是把「查到了一个数」说成「什么都没有」，规则引擎会把它读成 absent。
 */
class PrometheusHttpClientScalarTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HttpServer server;
    private PrometheusHttpClient client;

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
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws java.io.IOException {
                byte[] bytes = body.getBytes(UTF8);
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes);
                out.close();
            }
        });
        server.start();
    }

    @Test
    void 标量结果按二元组解析出数值() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\","
                + "\"result\":[1700000000,\"3.5\"]}}");

        assertThat(client.scalar("scalar(count(up))", 0)).isEqualTo(3.5);
    }

    @Test
    void 标量结果不带标签但仍是一个数而不是空结果() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\","
                + "\"result\":[1700000000,\"0\"]}}");

        // 值为 0 与「查不到」是两回事：0 是查到了 0。
        assertThat(client.scalar("scalar(count(up))", 0)).isEqualTo(0.0);
        assertThat(client.instant("scalar(count(up))", 0)).hasSize(1);
        assertThat(client.instant("scalar(count(up))", 0).get(0).labels()).isEmpty();
    }

    @Test
    void 标量结果是NaN时如实返回NaN而不是0() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\","
                + "\"result\":[1700000000,\"NaN\"]}}");

        assertThat(client.scalar("scalar(nonexistent_metric)", 0)).isNaN();
    }

    @Test
    void 表达式返回向量且恰好一条时取该条的值() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"job\":\"z-observer\"},\"value\":[1700000000,\"7\"]}]}}");

        assertThat(client.scalar("up", 0)).isEqualTo(7.0);
    }

    @Test
    void 表达式返回多条时序时抛异常而不是悄悄取第一条() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":["
                + "{\"metric\":{\"job\":\"a\"},\"value\":[1700000000,\"1\"]},"
                + "{\"metric\":{\"job\":\"b\"},\"value\":[1700000000,\"2\"]}]}}");

        assertThatThrownBy(() -> client.scalar("up", 0))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("不是标量");
    }

    @Test
    void 表达式没有匹配时序时标量是NaN而不是0() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");

        assertThat(client.scalar("up", 0)).isNaN();
    }

    @Test
    void 字符串结果不是数值必须报错() {
        respond("{\"status\":\"success\",\"data\":{\"resultType\":\"string\","
                + "\"result\":[1700000000,\"hello\"]}}");

        assertThatThrownBy(() -> client.scalar("\"hello\"", 0))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("字符串");
    }
}
