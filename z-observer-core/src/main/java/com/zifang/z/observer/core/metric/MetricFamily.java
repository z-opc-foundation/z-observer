package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 一个指标族：同名同类型的所有时序。
 *
 * <p>族是暴露层的渲染单位（一个 {@code # TYPE} 行 + N 条样本），也是基数防护的作用域 ——
 * 限制必须落在族上：整个进程总时序数看起来可能很合理，但单条指标名底下
 * 按 userId 铺开就足以拖垮收端，而总量指标对此毫无反应。
 */
final class MetricFamily {

    private final String name;
    private final String help;
    private final MetricRegistry.MetricKind kind;
    private final int maxSeries;

    private final ConcurrentMap<String, Metric> series = new ConcurrentHashMap<String, Metric>();
    /** 本族因基数超限被拒的次数。 */
    private final LongAdder rejected = new LongAdder();

    MetricFamily(String name, String help, MetricRegistry.MetricKind kind, int maxSeries) {
        this.name = name;
        this.help = help == null ? "" : help;
        this.kind = kind;
        this.maxSeries = maxSeries;
    }

    String name() {
        return name;
    }

    String help() {
        return help;
    }

    MetricRegistry.MetricKind kind() {
        return kind;
    }

    int seriesCount() {
        return series.size();
    }

    long rejectedCount() {
        return rejected.sum();
    }

    List<Metric> series() {
        List<Metric> out = new ArrayList<Metric>(series.values());
        // 排序让每次 scrape 的输出字节稳定：否则指标本身没变，diff 里全是无序抖动，
        // 真有变化时反而被淹没。
        java.util.Collections.sort(out, new Comparator<Metric>() {
            @Override
            public int compare(Metric a, Metric b) {
                return a.seriesKey().compareTo(b.seriesKey());
            }
        });
        return out;
    }

    Counter counter(Labels labels) {
        Metric existing = series.get(labels.seriesKey());
        if (existing instanceof Counter) {
            return (Counter) existing;
        }
        Counter created = Counter.create(name, help, labels);
        return (Counter) put(created);
    }

    Gauge gauge(Labels labels) {
        Metric existing = series.get(labels.seriesKey());
        if (existing instanceof Gauge) {
            return (Gauge) existing;
        }
        return (Gauge) put(Gauge.create(name, help, labels));
    }

    Histogram histogram(Labels labels, double[] buckets) {
        Metric existing = series.get(labels.seriesKey());
        if (existing instanceof Histogram) {
            return (Histogram) existing;
        }
        return (Histogram) put(Histogram.create(name, help, labels, buckets));
    }

    private Metric put(Metric m) {
        Metric prev = series.putIfAbsent(m.seriesKey(), m);
        if (prev != null) {
            return prev;
        }
        // 并发下可能刚好被别人抢先进来，多算一条。两个兜底：已经超限时立刻撤掉自己
        // 并计数，否则基数闸在竞争下形同虚设。
        if (series.size() > maxSeries) {
            series.remove(m.seriesKey(), m);
            rejected.increment();
            throw new CardinalityLimitExceededException(name, maxSeries, m.seriesKey());
        }
        return m;
    }

    /** 基数超限的专用异常：埋点方据此区分"自己写错了"和"配置太小"。 */
    static final class CardinalityLimitExceededException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        CardinalityLimitExceededException(String name, int max, String seriesKey) {
            super("指标 " + name + " 的时序数超过上限 " + max + "，新时序 " + seriesKey + " 被拒。"
                    + "通常是把高基数字段（如 userId、订单号）当成了标签；"
                    + "确实需要就调大 z.observer.metrics.max-series-per-metric");
        }
    }
}