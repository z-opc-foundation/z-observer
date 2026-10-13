package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 指标注册表。一个进程一个，持有全部指标族。
 *
 * <p>三件事在这里收口：<b>唯一性</b>（同名同类型才能注册，类型冲突必须炸）、
 * <b>基数防护</b>（超限时拒绝并计数，绝不静默丢弃）、<b>公共标签</b>
 *（app/env/instance 在此注入，业务侧不必人人带）。
 *
 * <p>基数超限的处理方式是全系统最反直觉、也最该守住的一条：<b>抛错 + 计数</b>。
 * Prometheus 自己的 remote-write 分片满了是静默丢的（业界公认的最差设计之一），
 * 但那是服务端且无从补救；埋点在业务进程里，静默丢的结果是"指标少了"
 * 而没有任何人知道少在哪。宁可让误埋点的那个请求失败一次。
 */
public final class MetricRegistry {

    public static final int DEFAULT_MAX_SERIES_PER_METRIC = 10_000;

    /** label 名白名单之外的维度在注册期就被拒。 */
    private static final java.util.regex.Pattern VALID_NAME =
            java.util.regex.Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    private static final java.util.regex.Pattern VALID_LABEL_NAME =
            java.util.regex.Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    /**
     * 保留标签名。le/quantile 由暴露层按桶和分位点生成，业务侧自己塞就会和
     * 生成的那些撞名，产出一组无法解析的时序；job/instance 由抓取端按目标注入。
     *
     * <p>只拦业务侧标签：instance 同时也是运维在公共标签里要配的身份字段，
     * 拿它去拦公共标签等于把正常配置挡在门外。
     */
private static final java.util.Set<String> RESERVED_LABELS =
            Collections.unmodifiableSet(new java.util.HashSet<String>(
                    java.util.Arrays.asList("le", "quantile", "job", "instance")));

    private final ConcurrentMap<String, MetricFamily> families = new ConcurrentHashMap<String, MetricFamily>();
    private final Labels commonLabels;
    private final int maxSeriesPerMetric;

    public MetricRegistry() {
        this(Labels.empty(), DEFAULT_MAX_SERIES_PER_METRIC);
    }

    public MetricRegistry(Labels commonLabels, int maxSeriesPerMetric) {
        if (maxSeriesPerMetric <= 0) {
            throw new IllegalArgumentException("maxSeriesPerMetric 必须为正: " + maxSeriesPerMetric);
        }
        this.commonLabels = commonLabels == null ? Labels.empty() : commonLabels;
        this.maxSeriesPerMetric = maxSeriesPerMetric;
    }

    public Labels commonLabels() {
        return commonLabels;
    }

    public int maxSeriesPerMetric() {
        return maxSeriesPerMetric;
    }

    public long rejectedSeriesCount() {
        long total = 0;
        for (MetricFamily f : families.values()) {
            total += f.rejectedCount();
        }
        return total;
    }

    // ---- 获取一条时序（埋点主入口）----

    public Counter counter(String name, String help, Labels labels) {
        return family(name, help, MetricKind.COUNTER).counter(mergedLabels(labels));
    }

    public Gauge gauge(String name, String help, Labels labels) {
        return family(name, help, MetricKind.GAUGE).gauge(mergedLabels(labels));
    }

    public Histogram histogram(String name, String help, Labels labels, double[] buckets) {
        return family(name, help, MetricKind.HISTOGRAM).histogram(mergedLabels(labels), buckets);
    }

    public Histogram histogram(String name, String help, Labels labels) {
        return histogram(name, help, labels, Histogram.DEFAULT_BUCKETS);
    }

    /** scrape 用：按名字取指标族，不存在返回 null（不要为空集合建一堆一次性对象）。 */
    public MetricFamily family(String name) {
        return families.get(name);
    }

    public List<MetricFamily> families() {
        List<MetricFamily> out = new ArrayList<MetricFamily>(families.values());
        Collections.sort(out, new java.util.Comparator<MetricFamily>() {
            @Override
            public int compare(MetricFamily a, MetricFamily b) {
                return a.name().compareTo(b.name());
            }
        });
        return out;
    }

    public int seriesCount() {
        int n = 0;
        for (MetricFamily f : families.values()) {
            n += f.seriesCount();
        }
        return n;
    }

    private MetricFamily family(String name, String help, MetricKind kind) {
        validateMetricName(name);
        MetricFamily f = families.get(name);
        if (f == null) {
            f = new MetricFamily(name, help, kind, maxSeriesPerMetric);
            MetricFamily existing = families.putIfAbsent(name, f);
            if (existing != null) {
                f = existing;
            }
        }
        // 同名不同类型不报错的话，暴露层会按先注册的那个类型渲染，
        // 另一个类型的指标被悄悄按错的类型解释，且永远查不出错。
        if (f.kind() != kind) {
            throw new IllegalStateException(
                    "指标 " + name + " 已按 " + f.kind() + " 注册，不能再按 " + kind + " 注册");
        }
        return f;
    }

    private Labels mergedLabels(Labels own) {
        Map<String, String> ownMap = own == null
                ? Collections.<String, String>emptyMap() : own.asMap();
        Map<String, String> merged = new LinkedHashMap<String, String>(ownMap);
        for (String name : ownMap.keySet()) {
            validateLabelName(name);
        }
        for (Map.Entry<String, String> e : commonLabels.asMap().entrySet()) {
            // 公共标签优先：app/instance 是身份，业务侧自己填错一个会让两条时序看起来是两个服务。
            merged.put(e.getKey(), e.getValue());
        }
        Labels.Builder result = Labels.builder();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            result.put(e.getKey(), e.getValue());
        }
        return result.build();
    }

    private static void validateMetricName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法指标名: " + name);
        }
    }

    private static void validateLabelName(String name) {
        if (!VALID_LABEL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("非法标签名: " + name);
        }
        if (RESERVED_LABELS.contains(name)) {
            throw new IllegalArgumentException("标签名 " + name + " 由系统生成，业务侧不可占用");
        }
    }

    /** 指标类型。用独立枚举而不是复用传输层的 {@code MetricType}：暴露口径与传输口径是两回事。 */
    public enum MetricKind {
        COUNTER("counter"),
        GAUGE("gauge"),
        HISTOGRAM("histogram");

        private final String prometheusName;

        MetricKind(String prometheusName) {
            this.prometheusName = prometheusName;
        }

        public String prometheusName() {
            return prometheusName;
        }
    }
}