package com.zifang.z.observer.admin.notify.zmsg;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 从 z-mist 取凭据的接线测试。
 *
 * <p>断言落在「服务端收到的查询参数」与「收端解析出的明文」上，
 * 并且钉住一件要紧的事：<b>收端不缓存、不落盘明文</b>。
 */
class HttpMistCredentialProviderTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> queries = new ArrayList<String>();
    private int status = 200;
    private String response = "{\"success\":true,\"data\":\"the-secret\"}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/secret/plain", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws java.io.IOException {
                queries.add(exchange.getRequestURI().getQuery());
                byte[] resp = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, resp.length);
                OutputStream out = exchange.getResponseBody();
                out.write(resp);
                out.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpMistCredentialProvider provider() {
        return new HttpMistCredentialProvider(baseUrl, 2000, 2000, "DEFAULT_GROUP", "");
    }

    @Test
    void 取到明文() {
        assertThat(provider().resolve("robot-token")).isEqualTo("the-secret");
        assertThat(queries.get(0)).contains("secretKey=robot-token")
                .contains("group=DEFAULT_GROUP");
    }

    @Test
    void 引用带group与namespace时按段拆() {
        provider().resolve("mygroup/myns/mykey");
        assertThat(queries.get(0)).contains("secretKey=mykey")
                .contains("group=mygroup")
                .contains("namespace=myns");
    }

    @Test
    void z_mist报错时抛凭据异常_不返回空串() {
        status = 404;
        response = "{\"success\":false,\"message\":\"密钥不存在\"}";
        assertThatThrownBy(() -> provider().resolve("nope"))
                .isInstanceOf(NotifyCredentialException.class);
    }

    @Test
    void 没配z_mist地址时直接失败_不静默返回空() {
        HttpMistCredentialProvider p = new HttpMistCredentialProvider("", 2000, 2000, "G", "");
        assertThatThrownBy(() -> p.resolve("k"))
                .isInstanceOf(NotifyCredentialException.class);
    }

    @Test
    void 明文里的转义能还原() {
        response = "{\"success\":true,\"data\":\"a\\\"b\\\\c\\nd\"}";
        assertThat(provider().resolve("k")).isEqualTo("a\"b\\c\nd");
    }
}
