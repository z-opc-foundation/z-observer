package com.zifang.z.observer.core.model;


import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个 span。链路的最小单元。
 *
 * <p>结构沿用业界事实标准（traceId / spanId / parentId，parentId 为空即根 span），
 * 存侧的复合键是 (traceId, spanId)，树在查询时按 parentId 重组 —— 存树会让并发写和
 * span 乱序到达都变难，而乱序到达在真实调用里是常态。
 */
public final class SpanData {

    private String traceId;
    private String spanId;
    /** 为 null 表示根 span。 */
    private String parentSpanId;
    private String name;
    private long startTimeMillis;
    /** 未结束时为 0。 */
    private long endTimeMillis;
    private SpanKind kind;
    /** 本 span 是否真的被记录。与 sampled 的组合见 {@link Builder#build()}。 */
    private boolean recorded;
    private Map<String, String> attributes;
    private String statusCode;
    private String statusMessage;

    // JsonUtil 按字段回填（详见 LogEvent 同处注释）：public 无参 + 非 final 字段。
    public SpanData() {
    }

    private SpanData(String traceId,
                     String spanId,
                     String parentSpanId,
                     String name,
                     long startTimeMillis,
                     long endTimeMillis,
                     SpanKind kind,
                     boolean recorded,
                     Map<String, String> attributes,
                     String statusCode,
                     String statusMessage) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.name = name;
        this.startTimeMillis = startTimeMillis;
        this.endTimeMillis = endTimeMillis;
        this.kind = kind;
        this.recorded = recorded;
        this.attributes = attributes == null || attributes.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
    }

    public static Builder builder() {
        return new Builder();
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

    public long startTimeMillis() {
        return startTimeMillis;
    }

    public long endTimeMillis() {
        return endTimeMillis;
    }

    public long durationMillis() {
        return endTimeMillis == 0 ? 0 : endTimeMillis - startTimeMillis;
    }

    public SpanKind kind() {
        return kind;
    }

    public boolean recorded() {
        return recorded;
    }

    public Map<String, String> attributes() {
        return attributes == null ? Collections.<String, String>emptyMap() : attributes;
    }

    public String statusCode() {
        return statusCode;
    }

    public String statusMessage() {
        return statusMessage;
    }

    // 派生值：收端拿到 parentSpanId 自己就能算，序列化出去等于让对端信一个
    // 可能和 parentSpanId 矛盾的字段。isXxx() 形状会被 Jackson 当 bean getter
    // 隐式收走，所以这里刻意起名 root()。
    public boolean root() {
        return parentSpanId == null || parentSpanId.isEmpty();
    }

    public enum SpanKind {
        /** 处理一次入站请求。 */
        SERVER,
        /** 发起一次出站调用。 */
        CLIENT,
        /** 进程内部的一段处理。 */
        INTERNAL,
        /** 消息生产。 */
        PRODUCER,
        /** 消息消费。 */
        CONSUMER
    }

    public static final class Builder {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String name;
        private long startTimeMillis;
        private long endTimeMillis;
        private SpanKind kind = SpanKind.INTERNAL;
        private boolean recorded = true;
        private boolean samplingDecisionMade;
        private boolean sampled;
        private Map<String, String> attributes;
        private String statusCode;
        private String statusMessage;

        public Builder traceId(String v) {
            this.traceId = v;
            return this;
        }

        public Builder spanId(String v) {
            this.spanId = v;
            return this;
        }

        public Builder parentSpanId(String v) {
            this.parentSpanId = v;
            return this;
        }

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder startTimeMillis(long v) {
            this.startTimeMillis = v;
            return this;
        }

        public Builder endTimeMillis(long v) {
            this.endTimeMillis = v;
            return this;
        }

        public Builder kind(SpanKind v) {
            this.kind = v;
            return this;
        }

        /**
         * 采样决策。业务侧回答「这条链路要不要留」，与 {@link #recorded(boolean)} 是两件事。
         *
         * <p>允许的三种组合：留且记（正常）、不留也不记（丢弃，正常）、
         * <b>留但不记</b>（只往下传 trace 上下文、不产生数据）。最后一种是合法的且有用的 ——
         * 它保证下游的 trace 连续，不会因为本层没采样而在链路上留下一个空洞。
         */
        public Builder sampling(boolean sampled, boolean recorded) {
            this.samplingDecisionMade = true;
            this.sampled = sampled;
            this.recorded = recorded;
            return this;
        }

        public Builder recorded(boolean v) {
            this.recorded = v;
            return this;
        }

        public Builder attributes(Map<String, String> v) {
            this.attributes = v;
            return this;
        }

        public Builder attribute(String k, String v) {
            if (k == null || v == null) {
                return this;
            }
            if (attributes == null) {
                attributes = new LinkedHashMap<String, String>();
            }
            attributes.put(k, v);
            return this;
        }

        public Builder statusCode(String v) {
            this.statusCode = v;
            return this;
        }

        public Builder statusMessage(String v) {
            this.statusMessage = v;
            return this;
        }

        public SpanData build() {
            if (traceId == null || traceId.isEmpty()) {
                throw new IllegalArgumentException("traceId 必填");
            }
            if (spanId == null || spanId.isEmpty()) {
                throw new IllegalArgumentException("spanId 必填");
            }
            // 「采样但不记录」是合法的（只传上下文不落数据，链路不洞）。反过来「决定丢弃却仍在记」
            // 不可能 —— 那会让收端拿到一批本该丢掉的 span，白烧存储和查询预算。
            if (samplingDecisionMade && !sampled && recorded) {
                throw new IllegalStateException(
                        "sampled=false 与 recorded=true 不能同时成立: 会记下本该丢弃的链路");
            }
            if (endTimeMillis != 0 && endTimeMillis < startTimeMillis) {
                throw new IllegalArgumentException("结束时间早于开始时间");
            }
            return new SpanData(traceId, spanId, parentSpanId, name, startTimeMillis, endTimeMillis,
                    kind, recorded, attributes, statusCode, statusMessage);
        }
    }
}