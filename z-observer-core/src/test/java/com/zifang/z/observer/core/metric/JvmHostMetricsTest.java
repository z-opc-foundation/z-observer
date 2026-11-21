package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JvmHostMetrics 测试。
 *
 * <p>用真实采集器跑（MXBean / 平台探针在测试进程里都可用），
 * 校验「refresh 之后核心 gauge 有读数、Prometheus 渲染得出文本」。
 * 平台差异只在 host_* 面上，jvm_* 面在任何 JDK 上都必须产出。
 */
class JvmHostMetricsTest {

    @Test
    void refreshPopulatesJvmGauges() {
        MetricRegistry registry = new MetricRegistry();
        JvmHostMetrics metrics = new JvmHostMetrics(registry);
        metrics.refresh();

        assertTrue(metrics.gaugeCount() > 0, "refresh 后应有 gauge 注册");
        assertGaugePositive(registry, "jvm_memory_heap_committed_bytes");
        assertGaugePositive(registry, "jvm_classes_loaded");
        assertGaugePositive(registry, "jvm_threads_live");
        assertGaugePositive(registry, "jvm_uptime_millis");
    }

    @Test
    void refreshTwiceIsIdempotentOnSeries() {
        MetricRegistry registry = new MetricRegistry();
        JvmHostMetrics metrics = new JvmHostMetrics(registry);
        metrics.refresh();
        int afterFirst = registry.seriesCount();
        metrics.refresh();

        assertEquals(afterFirst, registry.seriesCount(), "重复 refresh 不应新增时序");
    }

    @Test
    void renderContainsPrometheusText() {
        MetricRegistry registry = new MetricRegistry();
        new JvmHostMetrics(registry).refresh();

        String text = new PrometheusTextWriter(registry).render();
        assertTrue(text.contains("jvm_memory_heap_committed_bytes"), "暴露文本里要有堆内存指标");
        assertTrue(text.contains("# HELP jvm_memory_heap_committed_bytes"), "要有 HELP 行");
    }

    @Test
    void customSamplerListIsHonored() {
        MetricRegistry registry = new MetricRegistry();
        // 只挂 JVM 采集器：host_* 面完全不应出现
        JvmHostMetrics metrics = new JvmHostMetrics(registry,
                List.of(new com.zifang.util.monitor.jvm.JvmMonitor()));
        metrics.refresh();

        assertTrue(registry.family("host_memory_total_bytes") == null,
                "没挂主机采集器就不该有 host_* 指标");
        assertTrue(registry.family("jvm_memory_heap_used_bytes") != null);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new JvmHostMetrics(null)).getMessage().contains("registry"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new JvmHostMetrics(new MetricRegistry(), List.of())).getMessage().contains("samplers"));
    }

    private static void assertGaugePositive(MetricRegistry registry, String name) {
        MetricFamily family = registry.family(name);
        assertNotNull(family, "缺指标族 " + name);
        assertEquals(MetricRegistry.MetricKind.GAUGE, family.kind());
        double value = family.series().get(0).value();
        assertTrue(value > 0, name + " 应为正数，实际 " + value);
    }}
