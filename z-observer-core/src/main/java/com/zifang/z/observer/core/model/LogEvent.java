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

    private final long timestampMillis;
    private final String level;
    private final String logger;
    private final String thread;
    private final String message;
    private final String traceId;
    private final String spanId;
    private final ThrowableInfo throwable;
    private final Map<String, String> fields;

    // 批次对象要走 JSON 过网。不可变 + 非 bean 访问器（字段是 foo() 不是 getFoo()）
    // 的模型对 Jackson 默认视图是不可见的，不显式挂 creator 就是"能编译、线上首帧就抛
    // InvalidDefinitionException"。两侧共用这份模型，所以注解挂在模型上而不是某个
    // 私有的 mapper 上 —— 收端用谁的 ObjectMapper 都得能读。
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
        return fields;
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