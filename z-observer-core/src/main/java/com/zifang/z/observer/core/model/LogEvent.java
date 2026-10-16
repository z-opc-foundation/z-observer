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

    private long timestampMillis;
    private String level;
    private String logger;
    private String thread;
    private String message;
    private String traceId;
    private String spanId;
    private ThrowableInfo throwable;
    private Map<String, String> fields;

    // 两个消费方都不靠注解：Spring MVC（/api/logs/query、/api/traces/query 的返回体）
    // 走 bean getter 隐式序列化，键名与字段名一致；私有帧协议走 JsonUtil，
    // 它不用 creator 绑定、也不看访问器形状：new 一个无参对象后按**字段**
    // 回填，所以这里必须给出 public 无参构造器、字段也不能是 final（否则反射写不进去）。
    // 代价是反序列化那条路绕过了下面的归一化，fields 可能为 null —— 由 fields() 兜底。
    public LogEvent() {
    }

    private LogEvent(long timestampMillis,
                     String level,
                     String logger,
                     String thread,
                     String message,
                     String traceId,
                     String spanId,
                     ThrowableInfo throwable,
                     Map<String, String> fields) {
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
        return fields == null ? Collections.<String, String>emptyMap() : fields;
    }

    public long getTimestampMillis() { return timestampMillis; }
    public String getLevel() { return level; }
    public String getLogger() { return logger; }
    public String getThread() { return thread; }
    public String getMessage() { return message; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public ThrowableInfo getThrowable() { return throwable; }
    public Map<String, String> getFields() { return fields(); }

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