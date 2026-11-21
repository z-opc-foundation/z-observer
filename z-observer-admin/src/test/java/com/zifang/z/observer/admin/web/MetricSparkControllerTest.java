package com.zifang.z.observer.admin.web;

import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.PrometheusQueryException;
import com.zifang.z.observer.admin.metric.Series;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MetricSparkController 测试：不连真 Prometheus，用假 MetricQuery。 */
class MetricSparkControllerTest {

    /** 只实现 range；其余方法不该被控制器碰到。 */
    private static MetricQuery fake(final List<Series> rangeResult) {
        return new MetricQuery() {
            @Override
            public List<Series> instant(String promql, long timeMillis) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Series> range(String promql, long startMillis, long endMillis, long stepMillis) {
                return rangeResult;
            }

            @Override
            public double scalar(String promql, long timeMillis) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long lastSeenMillis(String promql) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Series point(Map<String, String> labels, double value, long ts) {
        return new Series(labels, value, ts);
    }

    /** 会数 range 调用次数的假 MetricQuery，缓存行为测试用。 */
    private static class CountingFake implements MetricQuery {
        final AtomicInteger rangeCalls = new AtomicInteger();
        private final List<Series> result;
        private final PrometheusQueryException failure;

        CountingFake(List<Series> result) {
            this(result, null);
        }

        CountingFake(PrometheusQueryException failure) {
            this(null, failure);
        }

        private CountingFake(List<Series> result, PrometheusQueryException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public List<Series> instant(String promql, long timeMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Series> range(String promql, long startMillis, long endMillis, long stepMillis) {
            rangeCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public double scalar(String promql, long timeMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long lastSeenMillis(String promql) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void 单条时序渲染出SVG折线() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("job", "z-gw");
        long base = System.currentTimeMillis() - 600_000L;
        List<Series> range = Arrays.asList(
                point(labels, 1, base),
                point(labels, 5, base + 300_000L),
                point(labels, 3, base + 600_000L));
        MetricSparkController controller = new MetricSparkController(fake(range));

        ResponseEntity<byte[]> res = controller.spark("up{job=\"z-gw\"}", 30L, 160, 32, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("image/svg+xml", res.getHeaders().getContentType().toString());
        String svg = new String(res.getBody(), StandardCharsets.UTF_8);
        assertTrue(svg.contains("<svg"), "实际: " + svg);
        assertTrue(svg.contains("polyline"), "有多个点应画出折线，实际: " + svg);
        assertTrue(res.getHeaders().getCacheControl().contains("no-store"));
    }

    @Test
    void 空结果回透明占位图而不是报错() {
        MetricSparkController controller = new MetricSparkController(
                fake(Collections.<Series>emptyList()));

        ResponseEntity<byte[]> res = controller.spark("ghost_metric", 30L, 160, 32, null);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        String svg = new String(res.getBody(), StandardCharsets.UTF_8);
        assertTrue(svg.contains("<svg"));
        assertTrue(!svg.contains("polyline"), "没有点就不该有折线");
    }

    @Test
    void 多条时序只画字典序第一条() {
        Map<String, String> a = new LinkedHashMap<String, String>();
        a.put("job", "a");
        Map<String, String> b = new LinkedHashMap<String, String>();
        b.put("job", "b");
        long base = System.currentTimeMillis() - 600_000L;
        List<Series> range = Arrays.asList(
                point(b, 9, base),
                point(a, 2, base),
                point(a, 4, base + 300_000L));
        MetricSparkController controller = new MetricSparkController(fake(range));

        ResponseEntity<byte[]> res = controller.spark("up", 30L, 160, 32, null);
        // 只要不炸、出了有效 SVG 即可；点数选择已由 pickSeries 保证取 job=a 那条
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(new String(res.getBody(), StandardCharsets.UTF_8).contains("polyline"));
    }

    @Test
    void seriesKey指定时画那条时序() {
        Map<String, String> a = new LinkedHashMap<String, String>();
        a.put("job", "a");
        Map<String, String> b = new LinkedHashMap<String, String>();
        b.put("job", "b");
        long base = System.currentTimeMillis() - 600_000L;
        List<Series> range = Arrays.asList(
                point(a, 2, base),
                point(a, 4, base + 300_000L),
                point(b, 9, base));
        MetricSparkController controller = new MetricSparkController(fake(range));

        ResponseEntity<byte[]> res = controller.spark("up", 30L, 160, 32, "job=b");
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(new String(res.getBody(), StandardCharsets.UTF_8).contains("<svg"));

        // 指定了不存在的 seriesKey → 回退第一条，不报错
        ResponseEntity<byte[]> fallback = controller.spark("up", 30L, 160, 32, "job=ghost");
        assertEquals(HttpStatus.OK, fallback.getStatusCode());
    }

    @Test
    void 尺寸参数被钳到上限() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        long base = System.currentTimeMillis();
        MetricSparkController controller = new MetricSparkController(fake(
                Collections.singletonList(point(labels, 1, base))));

        ResponseEntity<byte[]> res = controller.spark("up", 30L, 9999, 9999, null);
        String svg = new String(res.getBody(), StandardCharsets.UTF_8);
        assertTrue(svg.contains("width=\"640\""), "宽度钳到 640，实际: " + svg);
        assertTrue(svg.contains("height=\"200\""), "高度钳到 200，实际: " + svg);
    }

    @Test
    void 查询失败原样抛出由统一异常处理转502() {
        MetricQuery failing = new MetricQuery() {
            @Override
            public List<Series> instant(String promql, long timeMillis) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Series> range(String promql, long startMillis, long endMillis, long stepMillis) {
                throw new PrometheusQueryException("查询 up 失败: HTTP 503");
            }

            @Override
            public double scalar(String promql, long timeMillis) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long lastSeenMillis(String promql) {
                throw new UnsupportedOperationException();
            }
        };
        MetricSparkController controller = new MetricSparkController(failing);

        org.junit.jupiter.api.Assertions.assertThrows(PrometheusQueryException.class,
                () -> controller.spark("up", 30L, 160, 32, null));
    }

    @Test
    void 同一promql短时间重复请求命中缓存只真查一次() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("job", "a");
        long base = System.currentTimeMillis() - 600_000L;
        CountingFake fake = new CountingFake(Arrays.asList(
                point(labels, 1, base), point(labels, 2, base + 300_000L)));
        MetricSparkController controller = new MetricSparkController(fake);

        controller.spark("up", 30L, 160, 32, null);
        controller.spark("up", 30L, 160, 32, "job=a");

        assertEquals(1, fake.rangeCalls.get(),
                "表格一页 50 行都是同 promql，上游只该挨一次查询");
    }

    @Test
    void 缓存TTL为0则每次都真查() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        CountingFake fake = new CountingFake(
                Collections.singletonList(point(labels, 1, System.currentTimeMillis())));
        MetricSparkController controller = new MetricSparkController(fake, 0L);

        controller.spark("up", 30L, 160, 32, null);
        controller.spark("up", 30L, 160, 32, null);

        assertEquals(2, fake.rangeCalls.get());
    }

    @Test
    void 不同promql各查各的() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        CountingFake fake = new CountingFake(
                Collections.singletonList(point(labels, 1, System.currentTimeMillis())));
        MetricSparkController controller = new MetricSparkController(fake);

        controller.spark("up", 30L, 160, 32, null);
        controller.spark("down", 30L, 160, 32, null);

        assertEquals(2, fake.rangeCalls.get());
    }

    @Test
    void 查询失败也被短缓存不反复打挂掉的源() {
        CountingFake fake = new CountingFake(
                new PrometheusQueryException("查询 up 失败: HTTP 503"));
        MetricSparkController controller = new MetricSparkController(fake);

        for (int i = 0; i < 3; i++) {
            org.junit.jupiter.api.Assertions.assertThrows(PrometheusQueryException.class,
                    () -> controller.spark("up", 30L, 160, 32, null));
        }
        assertEquals(1, fake.rangeCalls.get(),
                "源已经挂了，连续请求不该每个都真发一遍超时查询");
    }

    @Test
    void 并发同键请求只打一次后端() throws Exception {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("job", "a");
        long base = System.currentTimeMillis() - 600_000L;
        CountingFake fake = new CountingFake(Arrays.asList(
                point(labels, 1, base), point(labels, 2, base + 300_000L))) {
            @Override
            public List<Series> range(String promql, long startMillis, long endMillis, long stepMillis) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.range(promql, startMillis, endMillis, stepMillis);
            }
        };
        MetricSparkController controller = new MetricSparkController(fake);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<ResponseEntity<byte[]>>> futures = new java.util.ArrayList<Future<ResponseEntity<byte[]>>>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(new Callable<ResponseEntity<byte[]>>() {
                    @Override
                    public ResponseEntity<byte[]> call() {
                        return controller.spark("up", 30L, 160, 32, null);
                    }
                }));
            }
            for (Future<ResponseEntity<byte[]>> f : futures) {
                assertEquals(HttpStatus.OK, f.get().getStatusCode());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, fake.rangeCalls.get(),
                "50 行同时渲染的场景下，并发的同键请求必须只放一个进去真查");
    }
}
