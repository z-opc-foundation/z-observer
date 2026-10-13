package com.zifang.z.observer.core.trace;

import com.zifang.z.observer.core.model.SpanData;
import com.zifang.z.observer.core.transport.EventCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracerTest {

    private final List<SpanData> spans = new CopyOnWriteArrayList<SpanData>();
    private final EventCollector collector = new EventCollector() {
        @Override
        public void accept(com.zifang.z.observer.core.model.LogEvent event) {
        }

        @Override
        public void accept(SpanData span) {
            spans.add(span);
        }
    };

    @AfterEach
    void clear() {
        TraceContext.set(null);
    }

    @Test
    void 根span没有parent且有traceId() {
        Tracer t = new Tracer(collector);
        Span s = t.startSpan("root", SpanData.SpanKind.SERVER);
        assertTrue(s.parentSpanId() == null || s.parentSpanId().isEmpty());
        assertNotNull(s.traceId());
        assertEquals(16, s.traceId().length());
        t.endSpan(s);
    }

    @Test
    void 子span继承traceId并指向父() {
        Tracer t = new Tracer(collector);
        Span root = t.startSpan("root", SpanData.SpanKind.SERVER);
        Span child = t.startSpan(root, "db", SpanData.SpanKind.CLIENT);
        assertEquals(root.traceId(), child.traceId(),
                "traceId 必须继承, 否则链路被劈成独立片段");
        assertEquals(root.spanId(), child.parentSpanId());
        t.endSpan(child);
        t.endSpan(root);
    }

    @Test
    void 两次根span的traceId不同() {
        Tracer t = new Tracer(collector);
        Span a = t.startSpan("a", SpanData.SpanKind.SERVER);
        Span b = t.startSpan("b", SpanData.SpanKind.SERVER);
        assertNotEquals(a.traceId(), b.traceId());
    }

    @Test
    void spanId在一条链路内唯一() {
        Tracer t = new Tracer(collector);
        Span root = t.startSpan("r", SpanData.SpanKind.SERVER);
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (int i = 0; i < 200; i++) {
            assertTrue(ids.add(t.startSpan(root, "s" + i, SpanData.SpanKind.INTERNAL).spanId()));
        }
    }

    @Test
    void endSpan产出带耗时的数据() throws InterruptedException {
        Tracer t = new Tracer(collector);
        Span s = t.startSpan("slow", SpanData.SpanKind.INTERNAL);
        Thread.sleep(15);
        t.endSpan(s);
        SpanData data = spans.get(0);
        assertTrue(data.durationMillis() >= 10, "实际 " + data.durationMillis());
        assertEquals("slow", data.name());
    }

    @Test
    void 重复end只产出一次() {
        Tracer t = new Tracer(collector);
        Span s = t.startSpan("x", SpanData.SpanKind.INTERNAL);
        t.endSpan(s);
        t.endSpan(s);
        assertEquals(1, spans.size(), "重复上报同一个 span 会让链路图出现重影");
    }

    @Test
    void 属性可加且进最终数据() {
        Tracer t = new Tracer(collector);
        Span s = t.startSpan("withattr", SpanData.SpanKind.CLIENT);
        s.attribute("db.system", "mysql").attribute("db.rows", "12");
        t.endSpan(s);
        assertEquals("mysql", spans.get(0).attributes().get("db.system"));
        assertEquals("12", spans.get(0).attributes().get("db.rows"));
    }

    @Test
    void error打上状态() {
        Tracer t = new Tracer(collector);
        Span s = t.startSpan("boom", SpanData.SpanKind.SERVER);
        s.error(new IllegalStateException("炸了"));
        t.endSpan(s);
        assertEquals("ERROR", spans.get(0).statusCode());
        assertTrue(spans.get(0).statusMessage().contains("炸了"));
    }

    @Test
    void endSpan后上下文回到父span() {
        Tracer t = new Tracer(collector);
        Span root = t.startSpan("r", SpanData.SpanKind.SERVER);
        TraceContext.set(root);
        Span child = t.startSpan(root, "c", SpanData.SpanKind.CLIENT);
        TraceContext.set(child);
        t.endSpan(child);
        assertSame(root, TraceContext.current(), "子 span 结束后当前上下文应回到父");
        t.endSpan(root);
        assertNull(TraceContext.current());
    }

    @Test
    void 跨线程池传播上下文() throws Exception {
        Tracer t = new Tracer(collector);
        ExecutorService raw = Executors.newFixedThreadPool(2);
        ExecutorService pool = TraceContext.propagating(raw);
        try {
            final Span root = t.startSpan("r", SpanData.SpanKind.SERVER);
            TraceContext.set(root);
            final AtomicReference<String> seen = new AtomicReference<String>();
            final CountDownLatch done = new CountDownLatch(1);
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    seen.set(TraceContext.currentTraceId());
                    done.countDown();
                }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(root.traceId(), seen.get(),
                    "ThreadLocal 跨不过线程池, 不包装就必然断链");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 未包装的线程池确实拿不到上下文_这正是要防的坑() throws Exception {
        ExecutorService raw = Executors.newFixedThreadPool(1);
        try {
            final Span root = new Tracer(collector).startSpan("r", SpanData.SpanKind.SERVER);
            TraceContext.set(root);
            final AtomicReference<Span> seen = new AtomicReference<Span>();
            final CountDownLatch done = new CountDownLatch(1);
            raw.submit(new Runnable() {
                @Override
                public void run() {
                    seen.set(TraceContext.current());
                    done.countDown();
                }
            });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(seen.get());
        } finally {
            raw.shutdownNow();
        }
    }

    @Test
    void 包装后目标线程原有的上下文不被污染() throws Exception {
        ExecutorService raw = Executors.newFixedThreadPool(1);
        ExecutorService pool = TraceContext.propagating(raw);
        try {
            Tracer t = new Tracer(collector);
            Span outer = t.startSpan("outer", SpanData.SpanKind.SERVER);
            TraceContext.set(outer);

            final CountDownLatch firstDone = new CountDownLatch(1);
            final AtomicReference<String> inFirst = new AtomicReference<String>();
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    inFirst.set(TraceContext.currentTraceId());
                    firstDone.countDown();
                }
            });
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            assertEquals(outer.traceId(), inFirst.get());

            // 同一线程上跑一个「未包装」的任务 —— 它不该看到上一次的上下文。
            // 线程池复用线程,包装层若不在 finally 里还原, traceId 就会一直粘在池线程上,
            // 之后每个走这条线程的请求都会被算进同一条链路。
            final CountDownLatch secondDone = new CountDownLatch(1);
            final AtomicReference<String> inSecond = new AtomicReference<String>();
            raw.submit(new Runnable() {
                @Override
                public void run() {
                    inSecond.set(TraceContext.currentTraceId());
                    secondDone.countDown();
                }
            });
            assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            assertNull(inSecond.get(), "任务结束后必须清理上下文, 否则复用的线程会串链路");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void callable包装也传播() throws Exception {
        Tracer t = new Tracer(collector);
        final Span root = t.startSpan("r", SpanData.SpanKind.SERVER);
        TraceContext.set(root);
        String got = TraceContext.wrap(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                return TraceContext.currentTraceId();
            }
        }).call();
        assertEquals(root.traceId(), got);
    }

    @Test
    void swap能暂存并恢复() {
        Tracer t = new Tracer(collector);
        Span a = t.startSpan("a", SpanData.SpanKind.INTERNAL);
        Span b = t.startSpan("b", SpanData.SpanKind.INTERNAL);
        TraceContext.set(a);
        Span prev = TraceContext.swap(b);
        assertSame(a, prev);
        assertSame(b, TraceContext.current());
        TraceContext.set(prev);
        assertSame(a, TraceContext.current());
    }

    @Test
    void collector为null时拒掉() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Tracer(null));
    }
}