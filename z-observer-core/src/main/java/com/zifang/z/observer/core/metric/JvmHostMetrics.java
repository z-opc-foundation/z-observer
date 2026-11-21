package com.zifang.z.observer.core.metric;

import com.zifang.util.monitor.jvm.JvmMonitor;
import com.zifang.util.monitor.os.OsMonitor;
import com.zifang.util.monitor.sampling.MetricsSampler;
import com.zifang.util.monitor.sampling.SampleSink;
import com.zifang.z.observer.core.model.Labels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JVM / 主机指标集 —— 把 SDK 暴露面从「业务埋了什么就有什么」补齐到
 * 「一个进程最起码有 jvm_* 和 host_* 可看」。
 *
 * <p>本域立项时最硬的断层就是暴露口径各自为政：业务指标有人埋，
 * JVM/主机面全靠各应用自发（实际是没人做）。这里的实现复用
 * z-util-monitor 的 {@link JvmMonitor} / {@link OsMonitor} 采集器 ——
 * 那边已经解决了跨平台读数（{@code com.sun.management} bean + 平台探针），
 * 本类只做一件事：把宽表采样翻译成 MetricRegistry 的 gauge。
 *
 * <p><b>拉模型的时机由使用方定</b>：本类不持任何线程，{@link #refresh()}
 * 采一次样、把有读数的 gauge 刷一遍。暴露端在每次 scrape 前调一次即可 ——
 * 没人抓就不白算，抓的时候值刚好新鲜。某读数在本平台拿不到（探针返回 -1）
 * 就不刷那条 gauge，让序列在 Prometheus 里自然过期，而不是伪造一个 0。
 *
 * <p>逐内存池 / 逐线程状态这类有名字前缀的指标按前缀规则映射；
 * 逐 GC 器的 count/time 在本类聚合成总量（Prometheus 端按 counter 语义
 * 用 rate() 时的口径更简单），不逐器拆条。
 */
public final class JvmHostMetrics {

    /** 源指标名 → Prometheus 名。只收核心面，逐池/逐状态走前缀规则。 */
    private static final Map<String, String> NAMES = buildNames();
    /** Prometheus 名 → 帮助文本。 */
    private static final Map<String, String> HELPS = buildHelps();

    private final MetricRegistry registry;
    private final List<MetricsSampler> samplers;
    private final ConcurrentMap<String, Gauge> gauges = new ConcurrentHashMap<String, Gauge>();

    public JvmHostMetrics(MetricRegistry registry) {
        this(registry, Arrays.<MetricsSampler>asList(new JvmMonitor(), new OsMonitor()));
    }

    /** 测试与定制口：换一组采集器（例如只采 JVM 面）。 */
    public JvmHostMetrics(MetricRegistry registry, List<MetricsSampler> samplers) {
        if (registry == null) {
            throw new IllegalArgumentException("registry 必填");
        }
        if (samplers == null || samplers.isEmpty()) {
            throw new IllegalArgumentException("samplers 必填");
        }
        this.registry = registry;
        this.samplers = new ArrayList<MetricsSampler>(samplers);
    }

    /**
     * 采一次样，把读得到的核心指标刷进注册表。
     *
     * <p>采集器单个失败不影响其余（与 z-util-monitor 编排器同一约定）。
     */
    public void refresh() {
        Map<String, Double> values = new LinkedHashMap<String, Double>();
        SampleSink sink = new SampleSink() {
            @Override
            public void put(String metric, double value) {
                values.put(metric, value);
            }

            @Override
            public void attribute(String key, String value) {
                // 属性（主机名、JVM 版本这类文本）不进指标面
            }
        };
        for (MetricsSampler sampler : samplers) {
            try {
                sampler.sample(sink);
            } catch (RuntimeException ignored) {
                // 一个采集器挂了不能拖累整份暴露
            }
        }

        double gcCount = 0;
        double gcTime = 0;
        boolean hasGc = false;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            String source = entry.getKey();
            if (source.startsWith("jvm.gc.") && source.endsWith(".count")) {
                gcCount += entry.getValue();
                hasGc = true;
                continue;
            }
            if (source.startsWith("jvm.gc.") && source.endsWith(".time")) {
                gcTime += entry.getValue();
                hasGc = true;
                continue;
            }
            String prometheusName = mapName(source);
            if (prometheusName == null) {
                continue;
            }
            gauge(prometheusName).set(entry.getValue());
        }
        if (hasGc) {
            gauge("jvm_gc_collection_count").set(gcCount);
            gauge("jvm_gc_time_millis").set(gcTime);
        }
    }

    /** 当前已注册的 gauge 数（测试与自检用）。 */
    public int gaugeCount() {
        return gauges.size();
    }

    private Gauge gauge(String prometheusName) {
        Gauge gauge = gauges.get(prometheusName);
        if (gauge == null) {
            Gauge created = registry.gauge(prometheusName, HELPS.get(prometheusName), Labels.empty());
            Gauge existing = gauges.putIfAbsent(prometheusName, created);
            gauge = existing == null ? created : existing;
        }
        return gauge;
    }

    /**
     * 源名 → Prometheus 名。精确表优先，逐池/逐线程状态两条前缀规则兜底。
     */
    private static String mapName(String source) {
        String exact = NAMES.get(source);
        if (exact != null) {
            return exact;
        }
        if (source.startsWith("jvm.memory.pool.")) {
            String rest = source.substring("jvm.memory.pool.".length());
            return "jvm_memory_pool_" + rest.replace('.', '_');
        }
        if (source.startsWith("jvm.thread.state.")) {
            String state = source.substring("jvm.thread.state.".length());
            return "jvm_threads_state_" + state.replace('.', '_');
        }
        return null;
    }

    private static Map<String, String> buildNames() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("jvm.memory.heap.used", "jvm_memory_heap_used_bytes");
        map.put("jvm.memory.heap.committed", "jvm_memory_heap_committed_bytes");
        map.put("jvm.memory.heap.max", "jvm_memory_heap_max_bytes");
        map.put("jvm.memory.heap.usage", "jvm_memory_heap_used_percent");
        map.put("jvm.memory.nonheap.used", "jvm_memory_nonheap_used_bytes");
        map.put("jvm.memory.nonheap.committed", "jvm_memory_nonheap_committed_bytes");
        map.put("jvm.gc.pause.last", "jvm_gc_pause_last_millis");
        map.put("jvm.gc.pause.max", "jvm_gc_pause_max_millis");
        map.put("jvm.class.loaded", "jvm_classes_loaded");
        map.put("jvm.class.unloaded", "jvm_classes_unloaded");
        map.put("jvm.thread.count", "jvm_threads_live");
        map.put("jvm.thread.peak", "jvm_threads_peak");
        map.put("jvm.thread.daemon", "jvm_threads_daemon");
        map.put("jvm.compile.total.time", "jvm_compile_time_millis");
        map.put("jvm.runtime.uptime", "jvm_uptime_millis");
        map.put("jvm.process.cpu.time", "jvm_process_cpu_time_ns");
        map.put("host.cpu.load", "host_cpu_load_percent");
        map.put("host.cpu.system.load.average", "host_load1");
        map.put("host.memory.total", "host_memory_total_bytes");
        map.put("host.memory.free", "host_memory_free_bytes");
        map.put("host.memory.used", "host_memory_used_bytes");
        map.put("host.memory.used.percent", "host_memory_used_percent");
        map.put("host.swap.total", "host_swap_total_bytes");
        map.put("host.swap.free", "host_swap_free_bytes");
        map.put("host.uptime", "host_uptime_millis");
        map.put("host.process.cpu.time", "host_process_cpu_time_ns");
        map.put("host.file.descriptors", "host_file_descriptors");
        map.put("host.file.descriptors.limit", "host_file_descriptors_limit");
        return map;
    }

    private static Map<String, String> buildHelps() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("jvm_memory_heap_used_bytes", "JVM 堆内存已用字节数");
        map.put("jvm_memory_heap_committed_bytes", "JVM 堆内存已提交字节数");
        map.put("jvm_memory_heap_max_bytes", "JVM 堆内存上限字节数");
        map.put("jvm_memory_heap_used_percent", "JVM 堆内存使用率（0-100）");
        map.put("jvm_memory_nonheap_used_bytes", "JVM 非堆内存已用字节数");
        map.put("jvm_memory_nonheap_committed_bytes", "JVM 非堆内存已提交字节数");
        map.put("jvm_gc_pause_last_millis", "最近一次 GC 停顿时长（毫秒）");
        map.put("jvm_gc_pause_max_millis", "进程内最大 GC 停顿时长（毫秒）");
        map.put("jvm_classes_loaded", "当前已加载类数");
        map.put("jvm_classes_unloaded", "累计卸载类数");
        map.put("jvm_threads_live", "当前线程数");
        map.put("jvm_threads_peak", "峰值线程数");
        map.put("jvm_threads_daemon", "守护线程数");
        map.put("jvm_compile_time_millis", "JIT 编译累计耗时（毫秒）");
        map.put("jvm_uptime_millis", "JVM 运行时长（毫秒）");
        map.put("jvm_process_cpu_time_ns", "进程累计 CPU 时间（纳秒）");
        map.put("jvm_gc_collection_count", "全部 GC 器累计次数");
        map.put("jvm_gc_time_millis", "全部 GC 器累计耗时（毫秒）");
        map.put("host_cpu_load_percent", "进程 CPU 使用率（0-100）");
        map.put("host_load1", "系统 1 分钟平均负载");
        map.put("host_memory_total_bytes", "宿主机物理内存总量（字节）");
        map.put("host_memory_free_bytes", "宿主机物理内存可用量（字节）");
        map.put("host_memory_used_bytes", "宿主机物理内存已用量（字节）");
        map.put("host_memory_used_percent", "宿主机物理内存使用率（0-100）");
        map.put("host_swap_total_bytes", "交换区总量（字节）");
        map.put("host_swap_free_bytes", "交换区可用量（字节）");
        map.put("host_uptime_millis", "宿主机运行时长（毫秒）");
        map.put("host_process_cpu_time_ns", "进程累计 CPU 时间（纳秒）");
        map.put("host_file_descriptors", "已打开文件描述符数");
        map.put("host_file_descriptors_limit", "文件描述符软上限");
        return map;
    }
}
