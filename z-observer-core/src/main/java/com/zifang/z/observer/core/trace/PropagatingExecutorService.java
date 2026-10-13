package com.zifang.z.observer.core.trace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 自动传播链路上下文的线程池包装。
 *
 * <p>包一次、全局生效，比在每个 {@code submit} 处手动包 {@code TraceContext.wrap(...)}
 * 可靠得多 —— 手动包装总会有人漏，漏掉的地方就是链路断点，而且不报错。
 *
 * <p>shutdown 等生命周期方法一律透传，包装层不该改变被包装线程池的行为。
 */
final class PropagatingExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    PropagatingExecutorService(ExecutorService delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为 null");
        }
        this.delegate = delegate;
    }

    ExecutorService unwrap() {
        return delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(TraceContext.wrap(command));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(TraceContext.wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(TraceContext.wrap(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(TraceContext.wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks), timeout, unit);
    }

    private static <T> Collection<? extends Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
        List<Callable<T>> wrapped = new ArrayList<Callable<T>>(tasks.size());
        for (Callable<T> t : tasks) {
            wrapped.add(TraceContext.wrap(t));
        }
        return wrapped;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
        return delegate.invokeAny(wrapAll(tasks), timeout, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}