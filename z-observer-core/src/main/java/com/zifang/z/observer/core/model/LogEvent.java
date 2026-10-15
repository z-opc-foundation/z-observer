package com.zifang.z.observer.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条日志事件。
 *
 * <p>message 是渲染后的文本，fields 是 MDC 里的键值对。两者都要留：message 给人看，
 * fields 给检索和聚合看（按 traceId 串起链路、按下单号捞全链路就是这么干的）。
 * 只留其中一边，要么没法搜、要么没法读。
 */
public final class LogEvent {

    private long timestampMillis;
    private String level;
    private String logger;
    private String thread;
    private String message;
    private String traceId;
    private String spanId;
    private ThrowableInfo throwable;
    private Map<String, String> fields;

    // 这一组注解是**两个**消费方共用的，所以必须留着：
    //   1) Spring MVC —— /api/logs/query 与 /api/traces/query 直接返回 LogEvent/SpanData，
    //      序列化走的是容器里的 ObjectMapper。删掉就是 InvalidDefinitionException。
    //   2) 私有帧协议 —— 走网的序列化已换成 com.zifang.util.json.JsonUtil，而 JsonUtil
    //      只认字段名（这里 @JsonProperty 的取值恰好就是字段名，所以两边报文一致）。
    // 另外 JsonUtil 不用 creator 绑定、也不看访问器形状：它 new 一个无参对象后按**字段**
    // 回填，所以这里必须给出 public 无参构造器、字段也不能是 final（否则反射写不进去）。
    // 代价是反序列化那条路绕过了下面的归一化，fields 可能为 null —— 由 fields() 兜底。
    public LogEvent() {
    }

    @JsonCreator
    private LogEvent(@JsonProperty("timestampMillis") long timestampMillis,
                     @JsonProperty("level") String level,
                     @JsonProperty("logger") String logger,
                     @JsonProperty("thread") String thread,
                     @JsonProperty("message") String message,
                     @JsonProperty("traceId") String traceId,
                     @JsonProperty("spanId") String spanId,
                     @JsonProperty("throwable") ThrowableInfo throwable,
                     @JsonProperty("fields") Map<String, String> fields) {
        this.timestampMillis = timestampMillis;
        this.level = level;
        this.logger = logger;
        this.thread = thread;
        this.message = message;
        this.traceId = traceId;
        this.spanId = spanId;
        this.throwable = throwable;
        this.fields = fields == null || fields.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(fields));
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonProperty("timestampMillis")
    public long timestampMillis() {
        return timestampMillis;
    }

    @JsonProperty("level")
    public String level() {
        return level;
    }

    @JsonProperty("logger")
    public String logger() {
        return logger;
    }

    @JsonProperty("thread")
    public String thread() {
        return thread;
    }

    @JsonProperty("message")
    public String message() {
        return message;
    }

    @JsonProperty("traceId")
    public String traceId() {
        return traceId;
    }

    @JsonProperty("spanId")
    public String spanId() {
        return spanId;
    }

    @JsonProperty("throwable")
    public ThrowableInfo throwable() {
        return throwable;
    }

    @JsonProperty("fields")
    public Map<String, String> fields() {
        return fields == null ? Collections.<String, String>emptyMap() : fields;
    }

    public static final class Builder {
        private long timestampMillis;
        private String level;
        private String logger;
        private String thread;
        private String message;
        private String traceId;
        private String spanId;
        private ThrowableInfo throwable;
        private Map<String, String> fields;

        public Builder timestampMillis(long v) {
            this.timestampMillis = v;
            return this;
        }

        public Builder level(String v) {
            this.level = v;
            return this;
        }

        public Builder logger(String v) {
            this.logger = v;
            return this;
        }

        public Builder thread(String v) {
            this.thread = v;
            return this;
        }

        public Builder message(String v) {
            this.message = v;
            return this;
        }

        public Builder traceId(String v) {
            this.traceId = v;
            return this;
        }

        public Builder spanId(String v) {
            this.spanId = v;
            return this;
        }

        public Builder throwable(ThrowableInfo v) {
            this.throwable = v;
            return this;
        }

        public Builder fields(Map<String, String> v) {
            this.fields = v;
            return this;
        }

        public LogEvent build() {
            return new LogEvent(timestampMillis, level, logger, thread, message,
                    traceId, spanId, throwable, fields);
        }
    }
}