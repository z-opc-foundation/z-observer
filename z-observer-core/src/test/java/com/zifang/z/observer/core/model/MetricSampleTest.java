package com.zifang.z.observer.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetricSampleTest {

    @Test
    void seriesKey由指标名与标签共同确定() {
        MetricSample s = MetricSample.of("http.duration",
                Labels.builder().put("uri", "/x").build(), MetricType.HISTOGRAM, 1000L, 5);
        assertEquals("http.duration{uri=/x}", s.seriesKey());
    }

    @Test
    void 无标签时seriesKey就是指标名() {
        assertEquals("jvm.threads",
                MetricSample.of("jvm.threads", Labels.empty(), MetricType.GAUGE, 1L, 2).seriesKey());
    }

    @Test
    void 直方图桶数与边界不等长时拒掉() {
        assertThrows(IllegalArgumentException.class, () -> MetricSample.histogram(
                "h", Labels.empty(), 1L, new double[]{1, 2}, new double[]{10}));
    }

    @Test
    void 直方图数组是拷贝的_外部改不到已建的样本() {
        double[] counts = {1, 2, 3};
        MetricSample s = MetricSample.histogram("h", Labels.empty(), 1L, counts, new double[]{1, 2, 3});
        counts[0] = 999;
        assertEquals(1.0, s.bucketBounds()[0], 0.0001);
    }

    @Test
    void 摘要取值与分位点不等长时拒掉() {
        assertThrows(IllegalArgumentException.class, () -> MetricSample.summary(
                "s", Labels.empty(), 1L, new double[]{1}, new double[]{0.5, 0.9}));
    }
}
