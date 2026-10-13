package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricRegistryTest {

    private static Labels labels(String k, String v) {
        return Labels.builder().put(k, v).build();
    }

    @Test
    void 同名同标签返回同一条时序() {
        MetricRegistry r = new MetricRegistry();
        Counter a = r.counter("req", "请求数", labels("k", "v"));
        Counter b = r.counter("req", "请求数", labels("k", "v"));
        assertSame(a, b, "同一时序必须复用，否则埋点处的两次 inc 会落在两个对象上，指标只算一半");
    }

    @Test
    void 标签顺序不同仍是同一条时序() {
        MetricRegistry r = new MetricRegistry();
        Counter a = r.counter("req", "", Labels.builder().put("a", "1").put("b", "2").build());
        Counter b = r.counter("req", "", Labels.builder().put("b", "2").put("a", "1").build());
        assertSame(a, b);
    }

    @Test
    void 公共标签被注入() {
        MetricRegistry r = new MetricRegistry(
                Labels.builder().put("app", "z-ops").put("env", "prod").build(), 100);
        Counter c = r.counter("req", "", Labels.empty());
        assertEquals("z-ops", c.labels().asMap().get("app"));
        assertEquals("prod", c.labels().asMap().get("env"));
    }

    @Test
    void 公共标签覆盖业务侧同名字段_业务侧不能伪造身份() {
        MetricRegistry r = new MetricRegistry(Labels.builder().put("app", "z-ops").build(), 100);
        Counter c = r.counter("req", "", labels("app", "别人"));
        assertEquals("z-ops", c.labels().asMap().get("app"));
    }

    @Test
    void 同名不同类型拒掉() {
        MetricRegistry r = new MetricRegistry();
        r.counter("x", "", Labels.empty());
        assertThrows(IllegalStateException.class, () -> r.gauge("x", "", Labels.empty()));
    }

    @Test
    void 保留标签名拒掉() {
        MetricRegistry r = new MetricRegistry();
        assertThrows(IllegalArgumentException.class, () -> r.counter("x", "", labels("le", "1")));
        assertThrows(IllegalArgumentException.class, () -> r.counter("x", "", labels("job", "j")));
    }

    @Test
    void 非法指标名与标签名拒掉() {
        MetricRegistry r = new MetricRegistry();
        assertThrows(IllegalArgumentException.class, () -> r.counter("1bad", "", Labels.empty()));
        assertThrows(IllegalArgumentException.class, () -> r.counter("ok", "", labels("a-b", "v")));
    }

    @Test
    void 基数超限抛错且计数_绝不静默丢() {
        MetricRegistry r = new MetricRegistry(Labels.empty(), 2);
        r.counter("x", "", labels("id", "1"));
        r.counter("x", "", labels("id", "2"));
        assertThrows(MetricFamily.CardinalityLimitExceededException.class,
                () -> r.counter("x", "", labels("id", "3")));
        assertEquals(1, r.rejectedSeriesCount(), "被拒的时序必须留下痕迹，否则指标静默少一条");
        assertEquals(2, r.seriesCount());
    }

    @Test
    void 超限后原有时序仍可用() {
        MetricRegistry r = new MetricRegistry(Labels.empty(), 1);
        Counter a = r.counter("x", "", labels("id", "1"));
        try {
            r.counter("x", "", labels("id", "2"));
        } catch (MetricFamily.CardinalityLimitExceededException ignored) {
            // 预期
        }
        a.inc();
        assertEquals(1d, a.value(), "一次被拒不该污染其它时序");
    }

    @Test
    void 并发取同一条时序只创建一个() throws InterruptedException {
        final MetricRegistry r = new MetricRegistry();
        final int threads = 16;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final Counter[] seen = new Counter[threads];
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            seen[idx] = r.counter("x", "", labels("k", "v"));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        for (int i = 1; i < threads; i++) {
            assertSame(seen[0], seen[i]);
        }
        assertEquals(1, r.seriesCount());
    }

    @Test
    void families按名字排序_让每次输出字节稳定() throws InterruptedException {
        MetricRegistry r = new MetricRegistry();
        r.counter("z_last", "", Labels.empty());
        r.counter("a_first", "", Labels.empty());
        assertEquals("a_first", r.families().get(0).name());
        assertNotNull(r.family("z_last"));
    }
}