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
 * 降级行为：Prometheus 不可达 / 报错时，<b>失败要可见</b>。
 *
 * <p>这条是本卡验收第 3 项的落点。降级口径只有一条：
 * <b>查询失败绝不返回空列表冒充成功</b>。
 *
 * <p>为什么这么要紧：空列表在规则引擎眼里是「这条时序不存在」⇒ 触发 absent 告警；
 * 而真实原因是「Prometheus 问不动」⇒ 这时候最该闭嘴。两者混在一起，
 * 就是监控系统在自己最需要它的时候自己坏掉，还顺手喊醒一批人。
 */
class PrometheusHttpClientDegradationTest {

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

    private void respond(final int code, final String body) {
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws java.io.IOException {
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
    void Prometheus不可达时抛异常而不是返回空列表() {
        // 端口 1 上不会有东西在听：连接被拒 = 最典型的「问不动 Prometheus」。
        PrometheusHttpClient unreachable = new PrometheusHttpClient("http://127.0.0.1:1", 800);

        assertThatThrownBy(() -> unreachable.instant("up", 0))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("up");
    }

    @Test
    void 不可达时失败被记账且可读() {
        PrometheusHttpClient unreachable = new PrometheusHttpClient("http://127.0.0.1:1", 800);

        assertThat(unreachable.failureCount()).isZero();
        assertThat(unreachable.lastFailureMillis()).isEqualTo(-1L);
        assertThat(unreachable.lastFailureMessage()).isNull();

        try {
            unreachable.instant("up", 0);
        } catch (PrometheusQueryException expected) {
            // 预期内
        }

        assertThat(unreachable.failureCount()).isEqualTo(1L);
        assertThat(unreachable.lastFailureMillis()).isGreaterThan(0L);
        assertThat(unreachable.lastFailureMessage()).isNotNull();
    }

    @Test
    void 不可达时区间与标量查询同样抛异常() {
        PrometheusHttpClient unreachable = new PrometheusHttpClient("http://127.0.0.1:1", 800);

        assertThatThrownBy(() -> unreachable.range("up", 1000, 2000, 1000))
                .isInstanceOf(PrometheusQueryException.class);
        assertThatThrownBy(() -> unreachable.scalar("scalar(count(up))", 0))
                .isInstanceOf(PrometheusQueryException.class);
    }

    @Test
    void HTTP错误码时抛异常并记账() {
        respond(503, "{\"status\":\"error\",\"errorType\":\"unavailable\","
                + "\"error\":\"server is down\"}");

        assertThatThrownBy(() -> client.instant("up", 0))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("503");
        assertThat(client.failureCount()).isEqualTo(1L);
    }

    @Test
    void PromQL语法错时抛异常并记账() {
        respond(400, "{\"status\":\"error\",\"errorType\":\"bad_data\","
                + "\"error\":\"parse error at char 3\"}");

        assertThatThrownBy(() -> client.instant("up{", 0))
                .isInstanceOf(PrometheusQueryException.class)
                .hasMessageContaining("parse error");
        assertThat(client.failureCount()).isEqualTo(1L);
    }

    @Test
    void 响应不是JSON时抛异常并记账() {
        respond(200, "<html>502 Bad Gateway</html>");

        assertThatThrownBy(() -> client.instant("up", 0))
                .isInstanceOf(PrometheusQueryException.class);
        assertThat(client.failureCount()).isEqualTo(1L);
    }

    @Test
    void 查不到时序不计入失败() {
        // 「Prometheus 说这条时序不存在」是业务事实，不是采集面故障。
        respond(200, "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");

        assertThat(client.instant("up", 0)).isEmpty();
        assertThat(client.failureCount()).isZero();
        assertThat(client.lastFailureMillis()).isEqualTo(-1L);
    }
}
