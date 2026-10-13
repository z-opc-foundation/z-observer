package com.zifang.z.observer.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一批 span。 */
public final class SpanBatch {

    private final String instanceId;
    private final List<SpanData> spans;

    private SpanBatch(String instanceId, List<SpanData> spans) {
        this.instanceId = instanceId;
        this.spans = Collections.unmodifiableList(spans);
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
        return spans;
    }

    public int size() {
        return spans.size();
    }
}
