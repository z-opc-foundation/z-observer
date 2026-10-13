package com.zifang.z.observer.admin.scrape;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 打真的 HTTP 服务，不 mock {@code HttpURLConnection}。
 *
 * <p>要验的是三件在 mock 下测不到的事：超时真的生效、非 200 真的被当成失败、
 * 响应体真的按字节读（exposition 里的中文在按字节读时会变成乱码）。
 */
class ScraperTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HttpServer server;
    private int port;
    private final AtomicInteger acceptHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(String path, final int status, final String body) {
        server.createContext(path, new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws java.io.IOException {
                acceptHits.incrementAndGet();
                byte[] bytes = body.getBytes(UTF8);
                x.sendResponseHeaders(status, bytes.length);
                OutputStream out = x.getResponseBody();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
        });
    }

    private ScrapeTarget target(String path) {
        return new ScrapeTarget("app-1", "http://127.0.0.1:" + port + path);
    }

    @Test
    void 采到正文且中文不乱码() {
        String exposition = "# HELP z_gw 请求数\nz_gw_requests_total{app=\"z-gw\"} 42\n# 中文注释\n";
        handle("/metrics", 200, exposition);

        Scraper.Result r = new Scraper().scrape(target("/metrics"));

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.text()).contains("请求数").contains("中文注释").contains("42");
        assertThat(r.target().id()).isEqualTo("app-1");
    }

    @Test
    void 非200算失败不抛() {
        handle("/metrics", 503, "unavailable");

        Scraper.Result r = new Scraper().scrape(target("/metrics"));

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.error()).isEqualTo("HTTP 503");
        assertThat(r.text()).isNull();
    }

    @Test
    void 连不上算失败不抛_不拖垮整轮采集() {
        // 这个端口上没有服务：断言的是「失败被吞成结果」而不是「异常冒出去把整轮采集打断」
        Scraper.Result r = new Scraper(300).scrape(
                new ScrapeTarget("dead", "http://127.0.0.1:1/metrics"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.error()).isNotEmpty();
    }

    @Test
    void 一个target挂掉不影响下一个() {
        handle("/ok", 200, "z_ok 1");

        Scraper s = new Scraper(300);
        Scraper.Result bad = s.scrape(new ScrapeTarget("dead", "http://127.0.0.1:1/metrics"));
        Scraper.Result good = s.scrape(target("/ok"));

        assertThat(bad.isSuccess()).isFalse();
        assertThat(good.isSuccess()).isTrue();
        assertThat(s.failureCount()).isEqualTo(1);
        assertThat(s.successCount()).isEqualTo(1);
    }

    @Test
    void 超限整份丢弃而不是截断() {
        // 长度报 0 让 JDK 服务端走 chunked：定长响应在服务端会先把整份缓冲起来，
        // 客户端根本读不到「边下边超」这条路径，测的就不是 readBounded 了
        server.createContext("/huge", new HttpHandler() {
            @Override
            public void handle(HttpExchange x) throws java.io.IOException {
                x.sendResponseHeaders(200, 0);
                OutputStream out = x.getResponseBody();
                byte[] buf = new byte[1024 * 1024];
                try {
                    for (int i = 0; i < 51; i++) {
                        out.write(buf);
                    }
                } catch (java.io.IOException ignored) {
                    // 客户端超限后会断开，这边的写入异常正是「丢弃发生了」的证据
                } finally {
                    try {
                        out.close();
                    } catch (java.io.IOException ignored) {
                        // 同上
                    }
                }
            }
        });

        Scraper s = new Scraper(30000);
        Scraper.Result r = s.scrape(target("/huge"));

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.text()).isNull();
        assertThat(r.error()).contains("整份丢弃");
        assertThat(s.oversizeCount()).isEqualTo(1);
        assertThat(s.successCount()).isZero();
    }

    @Test
    void target校验拦掉非法地址() {
        assertThatThrownBy(() -> new ScrapeTarget("", "http://x/metrics"))
                .isInstanceOf(IllegalArgumentException.class);
        // 无协议的地址会走 file:/jar: 这类协议，行为不可控，必须在配置期就拒
        assertThatThrownBy(() -> new ScrapeTarget("a", "localhost:9100/metrics"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}