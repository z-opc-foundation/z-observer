package com.zifang.z.observer.core.trace;

import com.zifang.z.observer.core.model.SpanData;
import com.zifang.z.observer.core.transport.EventCollector;

import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 链路创建入口。
 *
 * <p><b>新 span 的 traceId 在无父时生成，有父时必须继承。</b> 这是链路能串起来的唯一依据：
 * 子 span 自造 traceId 就会变成独立根 span，整棵树被劈成互不相干的碎片，而接口仍然返回
 * 正常结果，不会有任何报错。
 */
public final class Tracer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /** 16 位 traceId 足够定位单次请求，且短到日志里不刺眼。 */
    private static final int TRACE_ID_BYTES = 8;
    private static final int SPAN_ID_BYTES = 8;

    private final EventCollector collector;
    private final boolean sampled;

    public Tracer(EventCollector collector) {
        this(collector, true);
    }

    public Tracer(EventCollector collector, boolean sampled) {
        if (collector == null) {
            throw new IllegalArgumentException("collector 不能为 null");
        }
        this.collector = collector;
        this.sampled = sampled;
    }

    public boolean isSampled() {
        return sampled;
    }

    /** 开一个根 span。 */
    public Span startSpan(String name, SpanData.SpanKind kind) {
        return startSpan(null, name, kind);
    }

    /** 以 {@code parent} 为父开 span；parent 为 null 时自建根。 */
    public Span startSpan(Span parent, String name, SpanData.SpanKind kind) {
        String traceId = parent == null ? newId(TRACE_ID_BYTES) : parent.traceId();
        String parentSpanId = parent == null ? null : parent.spanId();
        return new Span(traceId, newId(SPAN_ID_BYTES), parentSpanId, name,
                System.currentTimeMillis(), kind, parent);
    }

    /**
     * 开一个根 span 并设为当前上下文。这是业务埋点最常用的入口。
     * 当前已有 span 时不自动接上父 —— 隐式继承会让"这里到底属于哪条链路"变得不可见。
     */
    public Span startRoot(String name, SpanData.SpanKind kind) {
        Span span = startSpan(name, kind);
        TraceContext.set(span);
        return span;
    }

    /** 结束 span 并上报。同时把它从当前上下文摘掉，让外层 span 重新成为当前。 */
    public void endSpan(Span span) {
        if (span == null) {
            return;
        }
        if (TraceContext.current() == span) {
            TraceContext.set(span.parent);
        }
        // 只上报一次：end() 本身幂等，但这里若不拦，重复调用 endSpan 会把同一个 span
        // 送进队列两次，链路图上出现重影、耗时统计翻倍。
        if (span.markReported()) {
            collector.accept(span.end());
        }
    }

    static String newId(int bytes) {
        byte[] buf = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(buf);
        char[] out = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            int v = buf[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}