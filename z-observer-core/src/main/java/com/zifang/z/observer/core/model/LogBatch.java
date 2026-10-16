package com.zifang.z.observer.core.model;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一批日志事件。 */
public final class LogBatch {

    private String instanceId;
    private List<LogEvent> events;

    // JsonUtil 按字段回填（详见 LogEvent 同处注释）：public 无参 + 非 final 字段。
    // 注意这里刻意不校验 instanceId —— 收端要能读进缺字段的批次，那是"协议错位"的
    // 正常观测对象，不是构造失败。
    public LogBatch() {
    }

    private LogBatch(String instanceId,
                     List<LogEvent> events) {
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

    public String instanceId() {
        return instanceId;
    }

    public List<LogEvent> events() {
        return events == null ? Collections.<LogEvent>emptyList() : events;
    }

    public String getInstanceId() { return instanceId; }
    public java.util.List<LogEvent> getEvents() { return events(); }

    public int size() {
        return events().size();
    }
}
