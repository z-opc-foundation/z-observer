package com.zifang.z.observer.core.model;

/**
 * 一个指标样本。指标名 + 标签 唯一确定一条时序，value 是该时刻的值。
 *
 * <p>直方图和摘要的 value 分别是桶计数数组与分位数组，其余两种是单个 double。
 * 用同一个类承载四种类型，是为了让「上报通道」不必按指标类型分叉 —— 通道只搬样本，
 * 解释权在存储与查询层。
 */
public final class MetricSample {

    private final String name;
    private final Labels labels;
    private final MetricType type;
    private final long timestampMillis;
    private final double value;
    /** 直方图的桶上界，与 value 一一对应（value 此时存的是各桶的累计计数）。 */
    private final double[] bucketBounds;
    /** 摘要模式下各分位点的取值，与 quantiles 一一对应。 */
    private final double[] quantileValues;

    private MetricSample(String name, Labels labels, MetricType type, long timestampMillis,
                         double value, double[] bucketBounds, double[] quantileValues) {
        this.name = name;
        this.labels = labels;
        this.type = type;
        this.timestampMillis = timestampMillis;
        this.value = value;
        this.bucketBounds = bucketBounds;
        this.quantileValues = quantileValues;
    }

    public static MetricSample of(String name, Labels labels, MetricType type,
                                  long timestampMillis, double value) {
        return new MetricSample(name, labels, type, timestampMillis, value, null, null);
    }

    public static MetricSample histogram(String name, Labels labels, long timestampMillis,
                                         double[] cumulativeCounts, double[] bucketBounds) {
        if (cumulativeCounts == null || bucketBounds == null
                || cumulativeCounts.length != bucketBounds.length) {
            throw new IllegalArgumentException("直方图的累积计数与桶上界必须等长");
        }
        return new MetricSample(name, labels, MetricType.HISTOGRAM, timestampMillis,
                cumulativeCounts.length, cumulativeCounts.clone(), bucketBounds.clone());
    }

    public static MetricSample summary(String name, Labels labels, long timestampMillis,
                                       double[] quantileValues, double[] quantiles) {
        if (quantileValues == null || quantiles == null || quantileValues.length != quantiles.length) {
            throw new IllegalArgumentException("摘要的取值与分位点必须等长");
        }
        return new MetricSample(name, labels, MetricType.SUMMARY, timestampMillis,
                quantileValues.length, null, quantileValues.clone());
    }

    public String name() {
        return name;
    }

    public Labels labels() {
        return labels;
    }

    public MetricType type() {
        return type;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public double value() {
        return value;
    }

    public double[] bucketBounds() {
        return bucketBounds;
    }

    public double[] quantileValues() {
        return quantileValues;
    }

    /** 该样本所属时序的身份：指标名 + 标签集。存储层用它做排序键与去重键。 */
    public String seriesKey() {
        String labelPart = labels == null ? "" : labels.seriesKey();
        return labelPart.isEmpty() ? name : name + "{" + labelPart + "}";
    }

    @Override
    public String toString() {
        return type + " " + seriesKey() + "@" + timestampMillis + "=" + value;
    }
}