package com.zifang.z.observer.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一批日志事件。 */
public final class LogBatch {

    private final String instanceId;
    private final List<LogEvent> events;

    @JsonCreator
    private LogBatch(@JsonProperty("instanceId") String instanceId,
                     @JsonProperty("events") List<LogEvent> events) {
        this.instanceId = instanceId;
        this.events = Collections.unmodifiableList(
                events == null ? new ArrayList<LogEvent>() : new ArrayList<LogEvent>(events));
    }

    public static LogBatch of(String instanceId, List<LogEvent> events) {
        if (instanceId == null || instanceId.isEmpty()) {
            throw new IllegalArgumentException("instanceId 必填");
        }
        return new LogBatch(instanceId, new ArrayList<LogEvent>(events));
    }

    @JsonProperty("instanceId")
    public String instanceId() {
        return instanceId;
    }

    @JsonProperty("events")
    public List<LogEvent> events() {
        return events;
    }

    public int size() {
        return events.size();
    }
}
