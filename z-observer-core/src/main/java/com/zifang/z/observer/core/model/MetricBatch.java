package com.zifang.z.observer.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一批指标样本。批是为了把多次埋点合成一次网络往返，不是为了改变语义。 */
public final class MetricBatch {

    private final String instanceId;
    private final List<MetricSample> samples;

    private MetricBatch(String instanceId, List<MetricSample> samples) {
        this.instanceId = instanceId;
        this.samples = Collections.unmodifiableList(samples);
    }

    public static MetricBatch of(String instanceId, List<MetricSample> samples) {
        if (instanceId == null || instanceId.isEmpty()) {
            throw new IllegalArgumentException("instanceId 必填 —— 没有它, 重启后的实例无法与重启前区分");
        }
        return new MetricBatch(instanceId, new ArrayList<MetricSample>(samples));
    }

    public String instanceId() {
        return instanceId;
    }

    public List<MetricSample> samples() {
        return samples;
    }

    public int size() {
        return samples.size();
    }
}
