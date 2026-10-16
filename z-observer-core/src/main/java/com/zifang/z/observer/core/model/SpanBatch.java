package com.zifang.z.observer.core.model;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一批 span。 */
public final class SpanBatch {

    private String instanceId;
    private List<SpanData> spans;

    // JsonUtil 按字段回填（详见 LogEvent 同处注释）：public 无参 + 非 final 字段。
    public SpanBatch() {
    }

    private SpanBatch(String instanceId,
                      List<SpanData> spans) {
        this.instanceId = instanceId;
        this.spans = Collections.unmodifiableList(
                spans == null ? new ArrayList<SpanData>() : new ArrayList<SpanData>(spans));
    }

    public static SpanBatch of(String instanceId, List<SpanData> spans) {
        if (instanceId == null || instanceId.isEmpty()) {
            throw new IllegalArgumentException("instanceId 必填");
        }
        return new SpanBatch(instanceId, new ArrayList<SpanData>(spans));
    }

    public String instanceId() {
        return instanceId;
    }

    public List<SpanData> spans() {
        return spans == null ? Collections.<SpanData>emptyList() : spans;
    }

    public String getInstanceId() { return instanceId; }
    public java.util.List<SpanData> getSpans() { return spans(); }

    public int size() {
        return spans().size();
    }
}
