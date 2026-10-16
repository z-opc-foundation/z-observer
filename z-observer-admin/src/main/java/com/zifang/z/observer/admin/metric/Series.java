package com.zifang.z.observer.admin.metric;


import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一条时序在某一时刻的取值。 */
public final class Series {

    private final Map<String, String> labels;
    private final double value;
    private final long timestampMillis;

    public Series(Map<String, String> labels, double value, long timestampMillis) {
        this.labels = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(labels == null
                        ? Collections.<String, String>emptyMap() : labels));
        this.value = value;
        this.timestampMillis = timestampMillis;
    }

    // REST 直接返回体。bean getter 形状让 Jackson 隐式序列化，报文键与字段名一致；
    // seriesKey() / label() 不是 getter 形状，不会被收进报文。
    public Map<String, String> getLabels() {
        return labels;
    }

    public double getValue() {
        return value;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public Map<String, String> labels() {
        return labels;
    }

    public double value() {
        return value;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public String label(String name) {
        return labels.get(name);
    }

    /**
     * 稳定的时序标识：标签全量按字典序拼。
     *
     * <p>字典序而不是插入序 —— 标签的写入顺序在不同请求、不同实现之间不一致，
     * 用插入序拼出来的 key 同一个时序会算出两个值，告警分组就散了。
     */
    public String seriesKey() {
        StringBuilder sb = new StringBuilder();
        java.util.List<String> names = new java.util.ArrayList<String>(labels.keySet());
        Collections.sort(names);
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(n).append('=').append(labels.get(n));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return seriesKey() + " " + value + "@" + timestampMillis;
    }
}
