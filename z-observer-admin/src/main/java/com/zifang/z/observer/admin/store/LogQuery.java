package com.zifang.z.observer.admin.store;

import com.zifang.z.observer.core.model.LogEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一次日志检索的条件。 */
public final class LogQuery {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final String text;
    private final long fromMillis;
    private final long toMillis;
    private final String level;
    private final String logger;
    private final String traceId;
    private final Map<String, String> fields;
    private final int limit;

    private LogQuery(Builder b) {
        this.text = b.text;
        this.fromMillis = b.fromMillis;
        this.toMillis = b.toMillis;
        this.level = b.level;
        this.logger = b.logger;
        this.traceId = b.traceId;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<String, String>(b.fields));
        this.limit = b.limit;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String text() {
        return text;
    }

    public long fromMillis() {
        return fromMillis;
    }

    public long toMillis() {
        return toMillis;
    }

    public String level() {
        return level;
    }

    public String logger() {
        return logger;
    }

    public String traceId() {
        return traceId;
    }

    public Map<String, String> fields() {
        return fields;
    }

    public int limit() {
        return limit;
    }

    public boolean matches(LogEvent e) {
        long ts = e.timestampMillis();
        if (fromMillis > 0 && ts < fromMillis) {
            return false;
        }
        if (toMillis > 0 && ts > toMillis) {
            return false;
        }
        if (level != null && !level.equalsIgnoreCase(nullToEmpty(e.level()))) {
            return false;
        }
        if (logger != null && !logger.equals(e.logger())) {
            return false;
        }
        if (traceId != null && !traceId.equals(e.traceId())) {
            return false;
        }
        for (Map.Entry<String, String> f : fields.entrySet()) {
            String actual = e.fields().get(f.getKey());
            if (!f.getValue().equals(nullToEmpty(actual))) {
                return false;
            }
        }
        return true;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static final class Builder {
        private String text;
        private long fromMillis;
        private long toMillis;
        private String level;
        private String logger;
        private String traceId;
        private final Map<String, String> fields = new LinkedHashMap<String, String>();
        private int limit = DEFAULT_LIMIT;

        public Builder text(String v) {
            this.text = v;
            return this;
        }

        public Builder fromMillis(long v) {
            this.fromMillis = v;
            return this;
        }

        public Builder toMillis(long v) {
            this.toMillis = v;
            return this;
        }

        public Builder level(String v) {
            this.level = emptyToNull(v);
            return this;
        }

        public Builder logger(String v) {
            this.logger = emptyToNull(v);
            return this;
        }

        public Builder traceId(String v) {
            this.traceId = emptyToNull(v);
            return this;
        }

        public Builder field(String k, String v) {
            if (k != null && !k.isEmpty() && v != null) {
                this.fields.put(k, v);
            }
            return this;
        }

        public Builder limit(int v) {
            if (v <= 0) {
                throw new IllegalArgumentException("limit 必须为正: " + v);
            }
            // 硬上限不是为了省内存，是为了让「limit=1000000」这种误传参数不会把
            // 收端的响应体撑到把调用方自己拖垮。
            this.limit = Math.min(v, MAX_LIMIT);
            return this;
        }

        public LogQuery build() {
            if (fromMillis > 0 && toMillis > 0 && fromMillis > toMillis) {
                throw new IllegalArgumentException("时间窗起止颠倒");
            }
            return new LogQuery(this);
        }

        private static String emptyToNull(String v) {
            return v == null || v.isEmpty() ? null : v;
        }
    }
}
