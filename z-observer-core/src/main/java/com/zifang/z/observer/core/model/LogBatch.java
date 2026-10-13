package com.zifang.z.observer.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一批日志事件。 */
public final class LogBatch {

    private final String instanceId;
    private final List<LogEvent> events;

    private LogBatch(String instanceId, List<LogEvent> events) {
        this.instanceId = instanceId;
        this.events = Collections.unmodifiableList(events);
    }

    public static LogBatch of(String instanceId, List<LogEvent> events) {
        if (instanceId == null || instanceId.isEmpty()) {
            throw new IllegalArgumentException("instanceId 必填");
        }
        return new LogBatch(instanceId, new ArrayList<LogEvent>(events));
    }

    public String instanceId() {
        return instanceId;
    }

    public List<LogEvent> events() {
        return events;
    }

    public int size() {
        return events.size();
    }
}
