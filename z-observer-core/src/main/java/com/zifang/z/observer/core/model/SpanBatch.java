package com.zifang.z.observer.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一批 span。 */
public final class SpanBatch {

    private final String instanceId;
    private final List<SpanData> spans;

    @JsonCreator
    private SpanBatch(@JsonProperty("instanceId") String instanceId,
                      @JsonProperty("spans") List<SpanData> spans) {
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

    @JsonProperty("instanceId")
    public String instanceId() {
        return instanceId;
    }

    @JsonProperty("spans")
    public List<SpanData> spans() {
        return spans;
    }

    public int size() {
        return spans.size();
    }
}
