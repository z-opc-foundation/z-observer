package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;

import java.util.Arrays;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * 经典直方图：固定桶边界 + 桶内计数，暴露成 {@code _bucket{le="..."}} / {@code _sum} / {@code _count}。
 *
 * <p><b>只做经典直方图，不做原生直方图。</b> 原生直方图能把分布压进一个样本里，
 * 但客户端仍是各发各的经典桶，Prometheus 3.3 起默认开启原生模式后，
 * 混着发就会让 {@code histogram_quantile()} 算出 NaN。要走原生得整链统一改造，
 * 收益不抵这个坑。
 *
 * <p><b>桶边界一经定下不可改。</b> 改了等于换了指标名：{@code le} 的取值变了，
 * 老样本和新样本落在不同的时序上，查询会得到一段没有分位数的空洞。宁可
 * 重新注册一个新名字。
 *
 * <p>桶按累加计数存（每个桶记的是"落在本桶及以下"的总数），
 * 这样 scrape 直接读数组即可，代价是 observe 每次要往上所有桶都加一次 ——
 * 默认桶不多，这个开销换来的读取简洁性是划算的。
 */
public final class Histogram extends Metric {

    /** 默认桶，单位是调用方传入值的单位（Timer 用毫秒时桶也是毫秒）。 */
    public static final double[] DEFAULT_BUCKETS = {
            .005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10
    };

    private final double[] bucketBounds;
    /** 末位是 +Inf 溢出桶，长度 = 边界数 + 1。 */
    private final LongAdder[] bucketCounts;
    private final DoubleAdder sum = new DoubleAdder();
    private final LongAdder count = new LongAdder();

    private Histogram(String name, String help, Labels labels, double[] bucketBounds) {
        super(name, help, labels);
        double[] sorted = bucketBounds.clone();
        Arrays.sort(sorted);
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] == sorted[i - 1]) {
                throw new IllegalArgumentException("桶边界重复: " + sorted[i]);
            }
        }
        this.bucketBounds = sorted;
        this.bucketCounts = new LongAdder[sorted.length + 1];
        for (int i = 0; i < this.bucketCounts.length; i++) {
            this.bucketCounts[i] = new LongAdder();
        }
    }

    public static Histogram create(String name, String help, Labels labels, double[] bucketBounds) {
        if (bucketBounds == null || bucketBounds.length == 0) {
            throw new IllegalArgumentException("桶边界不能为空");
        }
        for (double b : bucketBounds) {
            // NaN 会让所有比较为 false，样本静默全落进溢出桶，指标看着正常实则全错。
            if (Double.isNaN(b) || Double.isInfinite(b)) {
                throw new IllegalArgumentException("桶边界不能是 NaN 或 Infinity: " + b);
            }
        }
        return new Histogram(name, help, labels, bucketBounds);
    }

    public static Histogram create(String name, String help, Labels labels) {
        return create(name, help, labels, DEFAULT_BUCKETS);
    }

    public void observe(double v) {
        // NaN 观察值不能进桶：它跟任何边界比较都是 false，会落进 +Inf 溢出桶，
        // 把溢出桶的计数和真实的长尾混在一起，事后没法分开。
        if (Double.isNaN(v)) {
            return;
        }
        int idx = bucketBounds.length;
        for (int i = 0; i < bucketBounds.length; i++) {
            if (v <= bucketBounds[i]) {
                idx = i;
                break;
            }
        }
        // 真实桶从 idx 往上一个不漏地累积；+Inf 桶是最后一位，只有真的溢出了才动它，
        // 否则每次观测都往溢出桶 +1，+Inf 计数会等于总数，_count 与桶之间永远对不上。
        int lastReal = bucketBounds.length - 1;
        for (int i = idx; i <= lastReal; i++) {
            bucketCounts[i].increment();
        }
        if (idx > lastReal) {
            bucketCounts[lastReal + 1].increment();
        }
        sum.add(v);
        count.increment();
    }

    public void observeMillis(long millis) {
        observe(millis);
    }

    @Override
    public double value() {
        return count.sum();
    }

    public double[] bounds() {
        return bucketBounds.clone();
    }

    public long totalCount() {
        return count.sum();
    }

    public double totalSum() {
        return sum.sum();
    }

    /** 累计桶计数，长度与 {@link #bounds()} 一致（末位是 +Inf，不含溢出桶）。 */
    public long[] cumulativeBucketCounts() {
        long[] out = new long[bucketBounds.length + 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = bucketCounts[i].sum();
        }
        return out;
    }

    public long overflowCount() {
        return bucketCounts[bucketCounts.length - 1].sum();
    }
}