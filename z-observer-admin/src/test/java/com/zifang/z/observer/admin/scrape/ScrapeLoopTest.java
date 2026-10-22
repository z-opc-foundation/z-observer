package com.zifang.z.observer.admin.scrape;

import com.zifang.z.config.common.model.ZNamingInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScrapeLoopTest {

    /**
     * 发现源：把 target 指向同一个真HTTP服务上的不同路径，
     * 路径决定这个 target 采得到还是 503。
     */
    private static final class FixedSource implements TargetSource {
        private final List<ScrapeTarget> targets = new ArrayList<ScrapeTarget>();
        private final String base;
        private RuntimeException fail;

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
            if (fail != null) {
                throw fail;
            }
            return new ArrayList<ScrapeTarget>(targets);
        }

        void setTargets(String... ids) {
            targets.clear();
            for (String id : ids) {
                targets.add(new ScrapeTarget(id, base + id + "/metrics"));
            }
        }
    }

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
                // 路径首段即 target id；没登记过的 id 一律 503，模拟「这个进程挂了」
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

    private void alive(String id) {
        bodies.put(id, "z_" + id + "_up 1");
    }

    @Test
    void 采到的正文按target归位() {
        alive("a");
        alive("b");
        ScrapeLoop.Round round = new ScrapeLoop(new FixedSource(base, "a", "b"),
                new TargetRegistry(), new Scraper(3000)).run(1000L);

        assertThat(round.okCount()).isEqualTo(2);
        assertThat(round.failCount()).isZero();
        assertThat(round.samples().get("a")).isEqualTo("z_a_up 1");
        assertThat(round.samples().get("b")).isEqualTo("z_b_up 1");
    }

    @Test
    void 一个target挂掉不影响同轮其它target() {
        alive("good");
        ScrapeLoop.Round round = new ScrapeLoop(new FixedSource(base, "good", "dead"),
                new TargetRegistry(), new Scraper(3000)).run(1000L);

        assertThat(round.okCount()).isEqualTo(1);
        assertThat(round.failCount()).isEqualTo(1);
        assertThat(round.errors().get(0)).startsWith("dead");
    }

    @Test
    void 实例消失被记成消失事件_供absent告警使用() {
        alive("a");
        alive("b");
        FixedSource src = new FixedSource(base, "a", "b");
        ScrapeLoop loop = new ScrapeLoop(src, new TargetRegistry(), new Scraper(3000));
        loop.run(1000L);

        src.setTargets("a");
        ScrapeLoop.Round round = loop.run(2000L);

        assertThat(round.newlyVanished()).hasSize(1);
        assertThat(round.newlyVanished().get(0).id()).isEqualTo("b");
        assertThat(loop.registry().isVanished("b")).isTrue();
    }

    @Test
    void 发现源挂了必须保留上一轮target_不能当成全员消失() {
        alive("a");
        alive("b");
        alive("c");
        FixedSource src = new FixedSource(base, "a", "b", "c");
        ScrapeLoop loop = new ScrapeLoop(src, new TargetRegistry(), new Scraper(3000));
        loop.run(1000L);

        src.fail = new IllegalStateException("z-config 连接超时");
        ScrapeLoop.Round round = loop.run(2000L);

        assertThat(round.isDiscoverFailed()).isTrue();
        // 关键：这一轮一个消失事件都没有，否则发现源抖一下就变全集群告警
        assertThat(round.newlyVanished()).isEmpty();
        assertThat(loop.registry().liveCount()).isEqualTo(3);
        assertThat(loop.discoverFailureCount()).isEqualTo(1);
    }

    @Test
    void 三件套缺一不可() {
        assertThatThrownBy(() -> new ScrapeLoop(null, new TargetRegistry(), new Scraper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void naming源按实例地址拼target_同名服务的多个pod互不覆盖() {
        ZNamingInstance i1 = instance("10.0.0.7", 9100);
        ZNamingInstance i2 = instance("10.0.0.8", 9100);
        ZNamingServiceStub naming = new ZNamingServiceStub(Arrays.asList(i1, i2));

        ZConfigNamingTargetSource src = new ZConfigNamingTargetSource(naming,
                Collections.singletonList("z-gw"), 9100);
        List<ScrapeTarget> targets = src.discover();

        assertThat(src.name()).isEqualTo("z-config-naming");
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).id()).isEqualTo("z-gw@10.0.0.7:9100");
        assertThat(targets.get(1).id()).isEqualTo("z-gw@10.0.0.8:9100");
        assertThat(targets.get(0).url())
                .isEqualTo("http://10.0.0.7:9100" + ScrapeTarget.DEFAULT_METRICS_PATH);
    }

    @Test
    void naming源不筛healthy_不健康的实例恰恰该去采一次() {
        ZNamingInstance bad = instance("10.0.0.7", 9100);
        bad.setHealthy(Boolean.FALSE);
        ZNamingServiceStub naming = new ZNamingServiceStub(Collections.singletonList(bad));

        assertThat(new ZConfigNamingTargetSource(naming,
                Collections.singletonList("z-gw"), 9100).discover()).hasSize(1);
    }

    @Test
    void naming源缺配置直接拒() {
        ZNamingServiceStub naming = new ZNamingServiceStub(
                Collections.<ZNamingInstance>emptyList());
        assertThatThrownBy(() -> new ZConfigNamingTargetSource(null,
                Collections.singletonList("z-gw"), 9100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZConfigNamingTargetSource(naming,
                Collections.<String>emptyList(), 9100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ZNamingInstance instance(String ip, int port) {
        ZNamingInstance i = new ZNamingInstance();
        i.setIp(ip);
        i.setPort(Integer.valueOf(port));
        return i;
    }

    private static final class ZNamingServiceStub
            implements com.zifang.z.config.client.naming.ZNamingService {
        private final List<ZNamingInstance> instances;

        ZNamingServiceStub(List<ZNamingInstance> instances) {
            this.instances = instances;
        }

        @Override
        public List<ZNamingInstance> getAllInstances(String serviceName) {
            return instances;
        }

        @Override
        public void registerInstance(String serviceName, String ip, int port) {
        }

        @Override
        public void registerInstance(String s, String ip, int port, String cluster) {
        }

        @Override
        public void deregisterInstance(String serviceName, String ip, int port) {
        }

        @Override
        public void deregisterInstance(String s, String ip, int port, String c) {
        }

        @Override
        public List<ZNamingInstance> selectInstances(String serviceName, boolean healthy) {
            return instances;
        }

        @Override
        public List<ZNamingInstance> selectInstances(String s, String c, boolean healthy) {
            return instances;
        }

        @Override
        public ZNamingInstance selectOneHealthyInstance(String serviceName) {
            return instances.isEmpty() ? null : instances.get(0);
        }

        @Override
        public void addListener(String serviceName,
                com.zifang.z.config.client.naming.listener.ZNamingListener listener) {
        }

        @Override
        public void removeListener(String serviceName,
                com.zifang.z.config.client.naming.listener.ZNamingListener listener) {
        }
    }
}