package com.zifang.z.observer.core.transport;

import com.zifang.z.observer.core.model.LogBatch;
import com.zifang.z.observer.core.model.LogEvent;
import com.zifang.z.observer.core.model.SpanBatch;
import com.zifang.z.observer.core.model.SpanData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchingReporterTest {

    /** 收集所有批次，供断言用。 */
    private static final class RecordingSink implements EventSink {
        final List<LogEvent> logs = new CopyOnWriteArrayList<LogEvent>();
        final List<SpanData> spans = new CopyOnWriteArrayList<SpanData>();
        volatile RuntimeException failWith;

        @Override
        public void send(LogBatch batch) {
            if (failWith != null) {
                throw failWith;
            }
            logs.addAll(batch.events());
        }

        @Override
        public void send(SpanBatch batch) {
            if (failWith != null) {
                throw failWith;
            }
            spans.addAll(batch.spans());
        }
    }

    private static LogEvent log(String msg) {
        return LogEvent.builder().timestampMillis(1).level("INFO").message(msg).build();
    }

    private static SpanData span(String id) {
        return SpanData.builder().traceId("t").spanId(id).startTimeMillis(1).build();
    }

    @Test
    void 队列满时丢弃并计数_绝不阻塞() {
        RecordingSink sink = new RecordingSink();
        // 容量 2, 且不 start() 后台线程 —— 队列不会被消费, 正好构造背压
        BatchingReporter r = new BatchingReporter("i-1", sink, 2, 10, 60_000);
        for (int i = 0; i < 10; i++) {
            r.accept(log("m" + i));
        }
        assertEquals(2, r.queuedCount(), "队列有界, 超出部分应被拒");
        assertEquals(8, r.droppedCount(), "丢了 8 条必须留下痕迹");
    }

    @Test
    void 入队不抛异常() {
        BatchingReporter r = new BatchingReporter("i-1", new RecordingSink(), 1, 10, 60_000);
        r.accept(log("a"));
        for (int i = 0; i < 100; i++) {
            r.accept(log("overflow" + i));
        }
        assertEquals(100, r.droppedCount());
    }

    @Test
    void drainOnce把日志和span分开成两批() {
        RecordingSink sink = new RecordingSink();
        BatchingReporter r = new BatchingReporter("i-1", sink, 1000, 100, 60_000);
        r.accept(log("a"));
        r.accept(span("s1"));
        r.accept(log("b"));
        r.drainOnce();
        assertEquals(2, sink.logs.size());
        assertEquals(1, sink.spans.size());
        assertEquals(3, r.sentCount(), "sentCount 数的是条目数不是批次数");
    }

    @Test
    void 批次带instanceId() {
        RecordingSink sink = new RecordingSink();
        final List<String> ids = new ArrayList<String>();
        EventSink capturing = new EventSink() {
            @Override
            public void send(LogBatch batch) {
                ids.add(batch.instanceId());
            }

            @Override
            public void send(SpanBatch batch) {
                ids.add(batch.instanceId());
            }
        };
        BatchingReporter r = new BatchingReporter("host-a", capturing, 100, 100, 60_000);
        r.accept(log("x"));
        r.drainOnce();
        assertEquals(Collections.singletonList("host-a"), ids);
        assertTrue(sink.logs.isEmpty());
    }

    @Test
    void 发送失败计入失败数_不重试不吞掉() {
        RecordingSink sink = new RecordingSink();
        sink.failWith = new IllegalStateException("收端连不上");
        BatchingReporter r = new BatchingReporter("i-1", sink, 100, 100, 60_000);
        r.accept(log("a"));
        r.accept(log("b"));
        r.drainOnce();
        assertEquals(2, r.sendFailureCount(), "失败必须留痕");
        assertEquals(0, r.sentCount());
    }

    @Test
    void 超过批大小的数据被分多批() {
        RecordingSink sink = new RecordingSink();
        BatchingReporter r = new BatchingReporter("i-1", sink, 1000, 10, 60_000);
        for (int i = 0; i < 35; i++) {
            r.accept(log("m" + i));
        }
        r.drainOnce();
        assertEquals(35, sink.logs.size());
        assertTrue(r.queuedCount() == 0, "应全部发完");
    }

    @Test
    void stop把剩余数据冲出去() {
        RecordingSink sink = new RecordingSink();
        BatchingReporter r = new BatchingReporter("i-1", sink, 1000, 10, 60_000);
        r.start();
        r.accept(log("最后一条"));
        r.stop();
        assertEquals(1, sink.logs.size(), "停机时最后一段数据不能丢, 那往往正是故障现场");
    }

    @Test
    void 后台线程定期flush低流量数据() throws InterruptedException {
        RecordingSink sink = new RecordingSink();
        BatchingReporter r = new BatchingReporter("i-1", sink, 1000, 100, 50);
        r.start();
        try {
            r.accept(log("只有一条"));
            long deadline = System.currentTimeMillis() + 5000;
            while (sink.logs.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, sink.logs.size(), "哪怕只有一条也要在 flush 间隔内发出去");
        } finally {
            r.stop();
        }
    }

    @Test
    void 构造参数非法时拒掉() {
        RecordingSink sink = new RecordingSink();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BatchingReporter("", sink));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BatchingReporter("i", sink, 0, 10, 100));
    }
}