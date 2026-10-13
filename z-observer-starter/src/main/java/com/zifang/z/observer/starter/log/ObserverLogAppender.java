package com.zifang.z.observer.starter.log;

import com.zifang.z.observer.core.model.LogEvent;
import com.zifang.z.observer.core.model.ThrowableInfo;
import com.zifang.z.observer.core.transport.EventCollector;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.util.ReadOnlyStringMap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * log4j2 Appender：把日志转成 {@link LogEvent} 交给上报通道。
 *
 * <p><b>上报失败绝不影响业务日志。</b> Appender 是在业务线程的日志调用栈里执行的，
 * 这里抛出的异常会让 log4j2 记一条 "appender failed"，但真正的问题是它会把业务代码
 * 正在打的那条日志一起吞掉 —— 观测出问题反过来成了故障的原因。所以整条链路上的
 * 异常就地消化成计数。
 *
 * <p>MDC 全量取走：里面通常是 traceId、订单号这类排查线索，也是日志与链路关联的
 * 唯一桥梁，漏了就再也串不起来。
 */
public class ObserverLogAppender extends AbstractAppender {

    private final EventCollector collector;
    private final LongAdder dropped = new LongAdder();
    private final LongAdder sent = new LongAdder();

    protected ObserverLogAppender(String name, Filter filter, EventCollector collector) {
        super(name, filter, null, true, null);
        this.collector = collector;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public long sentCount() {
        return sent.sum();
    }

    @Override
    public void append(org.apache.logging.log4j.core.LogEvent event) {
        try {
            collector.accept(convert(event));
            sent.increment();
        } catch (RuntimeException e) {
            dropped.increment();
        }
    }

    static LogEvent convert(org.apache.logging.log4j.core.LogEvent event) {
        LogEvent.Builder builder = LogEvent.builder()
                .timestampMillis(event.getTimeMillis())
                .level(event.getLevel() == null ? null : event.getLevel().name())
                .logger(event.getLoggerName())
                .thread(event.getThreadName())
                .message(event.getMessage() == null ? null : event.getMessage().getFormattedMessage());

        ReadOnlyStringMap context = event.getContextData();
        if (context != null && !context.isEmpty()) {
            Map<String, String> fields = new LinkedHashMap<String, String>();
            for (String key : context.toMap().keySet()) {
                fields.put(key, context.getValue(key));
            }
            builder.fields(fields);
        }
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            builder.throwable(ThrowableInfo.from(thrown));
        }
        return builder.build();
    }

    public static class Builder {
        private String name = "z-observer";
        private Filter filter;
        private EventCollector collector;

        public Builder setName(String v) {
            this.name = v;
            return this;
        }

        public Builder setFilter(Filter v) {
            this.filter = v;
            return this;
        }

        public Builder setCollector(EventCollector v) {
            this.collector = v;
            return this;
        }

        public ObserverLogAppender build() {
            if (collector == null) {
                // 没有 collector 还构造出 appender，等于静默收集后丢弃——最难查的一类问题
                throw new IllegalArgumentException("ObserverLogAppender 必须设置 collector");
            }
            return new ObserverLogAppender(name, filter, collector);
        }
    }
}