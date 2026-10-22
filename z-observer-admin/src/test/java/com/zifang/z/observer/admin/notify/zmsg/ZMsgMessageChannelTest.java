package com.zifang.z.observer.admin.notify.zmsg;

import com.zifang.z.observer.admin.notify.AlertNotification;
import com.zifang.z.observer.admin.notify.NotifyException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * z-msg 通道（跨进程 HTTP）的接线测试。
 *
 * <p>用真的 HTTP 服务（{@code com.sun.net.httpserver}）而不是 mock：
 * 要验的就是「发出去的字节长什么样」，断言得落在服务端收到的 body 上。
 */
class ZMsgMessageChannelTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> bodies = new ArrayList<String>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final List<String> paths = new ArrayList<String>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws java.io.IOException {
                paths.add(exchange.getRequestURI().getPath());
                bodies.add(read(exchange.getRequestBody()));
                byte[] resp = "{\"success\":true,\"data\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), resp.length);
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

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static AlertNotification notification() {
        return new AlertNotification(AlertNotification.Kind.FIRING,
                "z-ctc-admin", "z-ctc-admin-abc", "process_absent", "进程消失",
                "critical", "0", 1_700_000_000_000L, 0L,
                "[告警] 进程消失", "正文", null);
    }

    private ZMsgMessageChannel channel(String receiver) {
        return new ZMsgMessageChannel(baseUrl, "/api/msg/batch/submit", "SMS",
                "z.observer.alert", "", receiver, null, 2000, 2000);
    }

    @Test
    void 投递体带齐五个字段_并按模板参数交出去() {
        channel("13800000000").send(notification());

        assertThat(paths.get(0)).isEqualTo("/api/msg/batch/submit");
        String body = bodies.get(0);
        assertThat(body).contains("\"bizType\":\"z.observer.alert\"")
                .contains("\"channel\":\"SMS\"")
                .contains("\"receiver\":\"13800000000\"")
                .contains("\"app\":\"z-ctc-admin\"")
                .contains("\"instance\":\"z-ctc-admin-abc\"")
                .contains("\"rule\":\"进程消失\"")
                .contains("\"value\":\"0\"")
                .contains("\"time\":");
    }

    @Test
    void 服务端5xx算可重试() {
        status.set(503);
        assertThatThrownBy(() -> channel("13800000000").send(notification()))
                .isInstanceOf(NotifyException.class)
                .satisfies(e -> assertThat(((NotifyException) e).permanent()).isFalse());
    }

    @Test
    void 服务端4xx算永久失败() {
        status.set(400);
        assertThatThrownBy(() -> channel("13800000000").send(notification()))
                .isInstanceOf(NotifyException.class)
                .satisfies(e -> assertThat(((NotifyException) e).permanent()).isTrue());
    }

    @Test
    void 连不上算可重试() {
        ZMsgMessageChannel dead = new ZMsgMessageChannel("http://127.0.0.1:1",
                "/api/msg/batch/submit", "SMS", "z.observer.alert", "", "13800000000",
                null, 500, 500);
        assertThatThrownBy(() -> dead.send(notification()))
                .isInstanceOf(NotifyException.class)
                .satisfies(e -> assertThat(((NotifyException) e).permanent()).isFalse());
    }

    @Test
    void 收件人走z_mist引用_明文不出现在收端配置里() {
        final String secret = "https://oapi.dingtalk.com/robot/send?access_token=REAL";
        MistCredentialProvider provider = ref -> secret;
        ZMsgMessageChannel c = new ZMsgMessageChannel(baseUrl, "/api/msg/batch/submit",
                "IM_DINGTALK", "z.observer.alert", "DEFAULT_GROUP//robot-token",
                "", provider, 2000, 2000);

        c.send(notification());

        assertThat(bodies.get(0)).contains("\"receiver\":\"" + secret + "\"");
        assertThat(c.name()).isEqualTo("z-msg:IM_DINGTALK");
    }

    @Test
    void 没配收件人时不发出去_直接失败() {
        ZMsgMessageChannel c = new ZMsgMessageChannel(baseUrl, "/api/msg/batch/submit",
                "SMS", "z.observer.alert", "", "", null, 2000, 2000);
        assertThatThrownBy(() -> c.send(notification()))
                .isInstanceOf(NotifyException.class);
        assertThat(bodies).isEmpty();
    }
}
