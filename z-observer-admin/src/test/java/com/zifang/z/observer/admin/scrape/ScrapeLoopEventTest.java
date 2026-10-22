package com.zifang.z.observer.admin.scrape;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集循环的<b>事件口径</b>：抓不到、解析坏、实例消失，都要落成可查询的事件。
 *
 * <p>这是 absent 数据源的<b>产生端</b>：规则消费的是「这个 target 现在缺席吗」，
 * 所以这里的每一处分支都必须留痕，而不是只留一个失败计数。
 */
class ScrapeLoopEventTest {

    private com.sun.net.httpserver.HttpServer server;
    private String base;
    private final Map<String, String> bodies = new LinkedHashMap<String, String>();

    @BeforeEach
    void startServer() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        server.createContext("/", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange x) throws java.io.IOException {
                String id = x.getRequestURI().getPath().split("/")[1];
                String body = bodies.get(id);
                int code = body == null ? 503 : 200;
                byte[] bytes = (body == null ? "" : body).getBytes("UTF-8");
                x.sendResponseHeaders(code, bytes.length);
                java.io.OutputStream out = x.getResponseBody();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static final class FixedSource implements TargetSource {
        private final List<ScrapeTarget> targets = new ArrayList<ScrapeTarget>();
        private final String base;

        FixedSource(String base, String... ids) {
            this.base = base;
            setTargets(ids);
        }

        @Override
        public String name() {
            return "fixed";
        }

        @Override
        public List<ScrapeTarget> discover() {
            return new ArrayList<ScrapeTarget>(targets);
        }

        void setTargets(String... ids) {
            targets.clear();
            for (String id : ids) {
                targets.add(new ScrapeTarget(id, base + id + "/metrics"));
            }
        }
    }

    @Test
    void 抓不到要落事件_并且这个target进缺席名单() {
        bodies.put("good", "z_good_up 1");
        FixedSource src = new FixedSource(base, "good", "dead");
        ScrapeLoop loop = new ScrapeLoop(src, new TargetRegistry(), new Scraper(3000));

        ScrapeLoop.Round round = loop.run(1000L);

        assertThat(round.okCount()).isEqualTo(1);
        assertThat(round.failCount()).isEqualTo(1);
        assertThat(loop.status().absentTargets()).containsExactly("dead");
        assertThat(loop.status().failureCount()).isEqualTo(1);
        ScrapeEvent last = loop.status().events().get(loop.status().events().size() - 1);
        assertThat(last.type()).isEqualTo(ScrapeEvent.Type.SCRAPE_FAILED);
        assertThat(last.targetId()).isEqualTo("dead");
    }

    @Test
    void 解析有坏行按失败论_坏行要能说清是哪一行() {
        bodies.put("broken", "z_ok 1\nthis_line_has_no_value\n");
        FixedSource src = new FixedSource(base, "broken");
        ScrapeLoop loop = new ScrapeLoop(src, new TargetRegistry(), new Scraper(3000));

        ScrapeLoop.Round round = loop.run(1000L);

        assertThat(round.okCount()).isZero();
        assertThat(round.failCount()).isEqualTo(1);
        assertThat(round.errors().get(0)).contains("broken");
        assertThat(loop.status().parseFailureCount()).isEqualTo(1);
        assertThat(loop.status().absentTargets()).containsExactly("broken");
        assertThat(loop.status().status("broken").lastError()).contains("第 2 行");
    }

    @Test
    void 解析零失败时样本进round_条数对得上() {
        bodies.put("a", "# TYPE z_a counter\nz_a 1\nz_b 2\n");
        FixedSource src = new FixedSource(base, "a");
        ScrapeLoop loop = new ScrapeLoop(src, new TargetRegistry(), new Scraper(3000));

        ScrapeLoop.Round round = loop.run(1000L);

        assertThat(round.okCount()).isEqualTo(1);
        assertThat(round.parsedSamples().get("a")).hasSize(2);
        assertThat(loop.status().status("a").lastSampleCount()).isEqualTo(2);
        assertThat(loop.status().status("a").isUp()).isTrue();
    }

    @Test
    void 实例从发现结果里消失要落消失事件() {
        bodies.put("a", "z_a 1");
        bodies.put("b", "z_b 1");
        FixedSource src = new FixedSource(base, "a", "b");
        ScrapeLoop loop = new ScrapeLoop(src, new TargetRegistry(), new Scraper(3000));
        loop.run(1000L);

        src.setTargets("a");
        loop.run(2000L);

        boolean vanishedEvent = false;
        for (ScrapeEvent e : loop.status().events()) {
            if (e.type() == ScrapeEvent.Type.TARGET_VANISHED && "b".equals(e.targetId())) {
                vanishedEvent = true;
            }
        }
        assertThat(vanishedEvent).isTrue();
        assertThat(loop.status().absentTargets()).contains("b");
    }

    @Test
    void 发现源挂了要记事件_但不能把全员标成缺席() {
        bodies.put("a", "z_a 1");
        FixedSource src = new FixedSource(base, "a");
        ScrapeLoop loop = new ScrapeLoop(src, new TargetRegistry(), new Scraper(3000));
        loop.run(1000L);

        src.setTargets("a");
        // 让发现源直接抛异常：模拟 z-config 自己挂了
        TargetSource broken = new TargetSource() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public List<ScrapeTarget> discover() {
                throw new IllegalStateException("naming 不可用");
            }
        };
        ScrapeLoop brokenLoop = new ScrapeLoop(broken, loop.registry(), new Scraper(3000));
        ScrapeLoop.Round round = brokenLoop.run(2000L);

        assertThat(round.isDiscoverFailed()).isTrue();
        assertThat(brokenLoop.discoverFailureCount()).isEqualTo(1);
        // 发现源故障时，本轮不产生任何 per-target 的缺席结论
        assertThat(brokenLoop.status().absentTargets()).isEmpty();
    }
}
