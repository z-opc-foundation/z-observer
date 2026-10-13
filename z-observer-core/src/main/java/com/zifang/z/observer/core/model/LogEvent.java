package com.zifang.z.observer.core.model;

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

    private LogEvent(Builder b) {
        this.timestampMillis = b.timestampMillis;
        this.level = b.level;
        this.logger = b.logger;
        this.thread = b.thread;
        this.message = b.message;
        this.traceId = b.traceId;
        this.spanId = b.spanId;
        this.throwable = b.throwable;
        this.fields = b.fields == null || b.fields.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.fields));
    }

    public static Builder builder() {
        return new Builder();
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public String level() {
        return level;
    }

    public String logger() {
        return logger;
    }

    public String thread() {
        return thread;
    }

    public String message() {
        return message;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public ThrowableInfo throwable() {
        return throwable;
    }

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
            return new LogEvent(this);
        }
    }
}