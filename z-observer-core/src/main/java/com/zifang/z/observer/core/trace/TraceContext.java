package com.zifang.z.observer.core.trace;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * 链路上下文。traceId + spanId 挂在当前线程上。
 *
 * <p><b>ThreadLocal 不是 ThreadLocal 的问题，是它跨不过线程池。</b> 业务代码把任务丢进
 * {@code executor.submit(...)} 之后，任务跑在另一个线程上，ThreadLocal 里空空如也——
 * 于是从异步任务往里打的 span 全成了根 span，链路由此断成两截。这类错误不报错，
 * 查出来只是"链路图断在某个地方"，极难定位。
 *
 * <p>所以跨线程必须显式包装：{@link #wrap(Runnable)} / {@link #wrap(Callable)}。
 * 没有包装就断链，这是刻意的选择 —— 隐式传播要么靠 agent 织入（我们不引），
 * 要么靠 InheritableThreadLocal（在池化线程上同样失效，因为线程早已创建完毕）。
 */
public final class TraceContext {

    private static final ThreadLocal<Span> CURRENT = new ThreadLocal<Span>();

    private TraceContext() {
    }

    public static void set(Span span) {
        if (span == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(span);
        }
    }

    public static Span current() {
        return CURRENT.get();
    }

    public static String currentTraceId() {
        Span s = CURRENT.get();
        return s == null ? null : s.traceId();
    }

    public static String currentSpanId() {
        Span s = CURRENT.get();
        return s == null ? null : s.spanId();
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /** 暂存并返回原上下文，用于跨一段逻辑后恢复。 */
    public static Span swap(Span next) {
        Span prev = CURRENT.get();
        set(next);
        return prev;
    }

    /** 包装 Runnable，把当前上下文带进目标线程，执行后恢复目标线程原有的上下文。 */
    public static Runnable wrap(final Runnable task) {
        final Span captured = CURRENT.get();
        return new Runnable() {
            @Override
            public void run() {
                Span prev = CURRENT.get();
                set(captured);
                try {
                    task.run();
                } finally {
                    set(prev);
                }
            }
        };
    }

    public static <T> Callable<T> wrap(final Callable<T> task) {
        final Span captured = CURRENT.get();
        return new Callable<T>() {
            @Override
            public T call() throws Exception {
                Span prev = CURRENT.get();
                set(captured);
                try {
                    return task.call();
                } finally {
                    set(prev);
                }
            }
        };
    }

    /**
     * 把已有线程池换成传播上下文的版本。原来的实例仍可用，只是不再传播 ——
     * 这不是静默失效：{@link #propagating(ExecutorService)} 会在启动时打一条 WARN。
     */
    public static ExecutorService propagating(ExecutorService pool) {
        return new PropagatingExecutorService(pool);
    }
}