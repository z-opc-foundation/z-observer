package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricTypesTest {

    private static Counter counter() {
        return Counter.create("c", "", Labels.empty());
    }

    private static Gauge gauge() {
        return Gauge.create("g", "", Labels.empty());
    }

    private static Histogram histogram(double... bounds) {
        return Histogram.create("h", "", Labels.empty(), bounds);
    }

    @Test
    void counter只涨() {
        Counter c = counter();
        c.inc();
        c.inc(2.5);
        assertEquals(3.5d, c.value());
    }

    @Test
    void counter拒掉递减() {
        assertThrows(IllegalArgumentException.class, () -> counter().inc(-1));
    }

    @Test
    void gauge可增可减可赋值() {
        Gauge g = gauge();
        g.set(10);
        g.inc();
        g.dec();
        g.dec();
        assertEquals(9d, g.value());
        g.set(-3.5);
        assertEquals(-3.5d, g.value());
    }

    @Test
    void gauge的NaN不被抹掉() {
        Gauge g = gauge();
        g.set(Double.NaN);
        assertTrue(Double.isNaN(g.value()));
    }

    @Test
    void gauge并发增减不丢更新() throws InterruptedException {
        final Gauge g = gauge();
        int threads = 8, per = 10_000;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int sign = t % 2 == 0 ? 1 : -1;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            for (int i = 0; i < per; i++) {
                                g.add(sign);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        // 增减正好抵消；非原子读改写会剩下一个不为零的残值
        assertEquals(0d, g.value());
    }

    @Test
    void 直方图桶是累积的() {
        Histogram h = histogram(1, 5, 10);
        h.observe(0.5);
        h.observe(3);
        h.observe(7);
        h.observe(50);
        long[] counts = h.cumulativeBucketCounts();
        assertEquals(1, counts[0], "<=1 只有 0.5");
        assertEquals(2, counts[1], "<=5 累计 0.5 与 3");
        assertEquals(3, counts[2], "<=10 累计到 7");
        assertEquals(1, h.overflowCount(), ">10 只有 50");
        assertEquals(4, h.totalCount());
        assertEquals(60.5d, h.totalSum());
    }

    @Test
    void 桶边界排序后使用_注册顺序不影响结果() {
        Histogram h = histogram(10, 1, 5);
        assertEquals(1d, h.bounds()[0]);
        assertEquals(10d, h.bounds()[2]);
    }

    @Test
    void 拒绝重复与非法桶边界() {
        assertThrows(IllegalArgumentException.class, () -> histogram(1, 1));
        assertThrows(IllegalArgumentException.class, () -> histogram(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> histogram(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> histogram(new double[0]));
    }

    @Test
    void 观察值NaN被丢弃而不是污染桶() {
        Histogram h = histogram(1);
        h.observe(Double.NaN);
        assertEquals(0, h.totalCount(), "NaN 会跟所有边界比较为 false 而落进溢出桶");
        assertEquals(0, h.overflowCount());
    }

    @Test
    void 边界值落进其所属桶而不是下一个() {
        Histogram h = histogram(1, 2);
        h.observe(1d);
        h.observe(2d);
        long[] c = h.cumulativeBucketCounts();
        assertEquals(1, c[0]);
        assertEquals(2, c[1]);
        assertEquals(0, h.overflowCount());
    }
}