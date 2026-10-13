package com.zifang.z.observer.admin.metric;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    // 这个类型是 REST 的直接返回体。非 bean 访问器对 Jackson 默认视图不可见，
    // 不显式标注的话 /api/metrics/query 每次调用都抛
    // InvalidDefinitionException: No serializer found for class Series。
    // seriesKey() / label() 是派生值，不标就不该出现在报文里。
    @JsonProperty("labels")
    public Map<String, String> labels() {
        return labels;
    }

    @JsonProperty("value")
    public double value() {
        return value;
    }

    @JsonProperty("timestampMillis")
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
