package com.zifang.z.observer.core.trace;

import com.zifang.z.observer.core.model.SpanData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 正在进行的 span。结束时 {@link #end()} 产出 {@link SpanData} 上报。
 *
 * <p>状态是可变的，因为 span 的生命周期横跨一次方法调用：开始时只有名字，
 * 结束时才有耗时和状态。可变不构成问题 —— 一个 span 从产生到结束只在当前线程内流转。
 */
public final class Span {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String name;
    private final long startTimeMillis;
    private final SpanData.SpanKind kind;
    /** 父 span 引用，仅用于结束子 span 时把上下文还原给父。不参与序列化。 */
    final Span parent;

    private final Map<String, String> attributes = new LinkedHashMap<String, String>();
    private volatile long endTimeMillis;
    private volatile String statusCode;
    private volatile String statusMessage;
    private volatile boolean ended;
    private boolean reported;

    Span(String traceId, String spanId, String parentSpanId, String name,
         long startTimeMillis, SpanData.SpanKind kind, Span parent) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.name = name;
        this.startTimeMillis = startTimeMillis;
        this.kind = kind;
        this.parent = parent;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public String name() {
        return name;
    }

    public SpanData.SpanKind kind() {
        return kind;
    }

    public long startTimeMillis() {
        return startTimeMillis;
    }

    public Span attribute(String k, String v) {
        if (k != null && v != null) {
            synchronized (attributes) {
                attributes.put(k, v);
            }
        }
        return this;
    }

    public void error(Throwable t) {
        this.statusCode = "ERROR";
        if (t != null) {
            this.statusMessage = t.getClass().getName() + ": " + t.getMessage();
        }
    }

    public void status(String code, String message) {
        this.statusCode = code;
        this.statusMessage = message;
    }

    public boolean isEnded() {
        return ended;
    }

    /**
     * 结束 span 并产出上报数据。重复调用返回同一份结果 —— 上报入口可能被 try/finally
     * 和业务代码各调一次，两次上报同一个 span 会让链路里出现重影。
     */
    /** 首次返回 true，由 {@link Tracer#endSpan} 保证只上报一次。 */
    synchronized boolean markReported() {
        if (reported) {
            return false;
        }
        reported = true;
        return true;
    }

    public synchronized SpanData end() {
        if (ended) {
            return toData();
        }
        ended = true;
        endTimeMillis = System.currentTimeMillis();
        return toData();
    }

    private SpanData toData() {
        Map<String, String> attrs;
        synchronized (attributes) {
            attrs = attributes.isEmpty() ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        }
        return SpanData.builder()
                .traceId(traceId)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .name(name)
                .startTimeMillis(startTimeMillis)
                .endTimeMillis(endTimeMillis)
                .kind(kind)
                .attributes(attrs)
                .statusCode(statusCode)
                .statusMessage(statusMessage)
                .build();
    }
}