package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;

/**
 * 一条时序的累计器基类。「一条时序」= 指标名 + 标签集，二者共同确定身份。
 *
 * <p>这里放的是累计器而不是"一次采样结果"：埋点方拿到 Counter 就往上加，
 * 时刻值由 scrape 决定。这样业务线程不必关心采样节奏，也不会因为
 * 没赶上采样而丢掉最后一次累加。
 */
public abstract class Metric {

    private final String name;
    private final String help;
    protected final Labels labels;
    /** 该时序的身份键，标签集排序后再拼，与注册顺序无关。 */
    private final String seriesKey;

    protected Metric(String name, String help, Labels labels) {
        this.name = name;
        this.help = help;
        this.labels = labels == null ? Labels.empty() : labels;
        this.seriesKey = this.labels.seriesKey();
    }

    public String name() {
        return name;
    }

    public String help() {
        return help;
    }

    public Labels labels() {
        return labels;
    }

    public String seriesKey() {
        return seriesKey;
    }

    /** scrape 时刻该时序的值。 */
    public abstract double value();
}