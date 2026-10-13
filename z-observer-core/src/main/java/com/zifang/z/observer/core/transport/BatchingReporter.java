package com.zifang.z.observer.core.transport;

import com.zifang.z.observer.core.model.LogBatch;
import com.zifang.z.observer.core.model.LogEvent;
import com.zifang.z.observer.core.model.SpanBatch;
import com.zifang.z.observer.core.model.SpanData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量上报器：埋点线程只管入队，一个后台线程按批取走发给 {@link EventSink}。
 *
 * <p><b>队列满时丢弃并计数，绝不阻塞埋点线程。</b> 这是本类最重要的一条。
 * 观测通道是旁路：收端挂了、网络抖了、队列打满，如果这时候业务请求被阻塞甚至超时，
 * 那就是"监控把业务搞挂了"，本末倒置。所以满了就丢。
 *
 * <p>但丢了必须<b>留下痕迹</b>：{@link #droppedCount()} 是这样一件事会自己说话的唯一
 * 线索。这里刻意不重试也不阻塞 —— 补齐这两样才是另一个设计题（本地 WAL + 采样降级），
 * 不该塞进一个类里假装已经解决。
 *
 * <p>停机用 {@link #stop} 而不是依赖 finalizer：埋点线程可能在 flush 期间还在入队，
 * 静默关闭会让最后一段日志消失，而那往往正是出故障时最该留住的部分。
 */
public final class BatchingReporter implements EventCollector {

    private static final int DEFAULT_QUEUE_CAPACITY = 8192;
    private static final int DEFAULT_BATCH_SIZE = 512;
    private static final int DEFAULT_FLUSH_INTERVAL_MILLIS = 1000;

    private enum ItemKind {
        LOG, SPAN
    }

    private static final class Item {
        final ItemKind kind;
        final LogEvent log;
        final SpanData span;

        Item(LogEvent log) {
            this.kind = ItemKind.LOG;
            this.log = log;
            this.span = null;
        }

        Item(SpanData span) {
            this.kind = ItemKind.SPAN;
            this.log = null;
            this.span = span;
        }
    }

    private final String instanceId;
    private final EventSink delegate;
    private final BlockingQueue<Item> queue;
    private final int batchSize;
    private final long flushIntervalMillis;

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong sendFailures = new AtomicLong();

    private volatile boolean running = true;
    private Thread worker;

    public BatchingReporter(String instanceId, EventSink delegate) {
        this(instanceId, delegate, DEFAULT_QUEUE_CAPACITY, DEFAULT_BATCH_SIZE,
                DEFAULT_FLUSH_INTERVAL_MILLIS);
    }

    public BatchingReporter(String instanceId, EventSink delegate, int queueCapacity,
                            int batchSize, long flushIntervalMillis) {
        if (instanceId == null || instanceId.isEmpty()) {
            throw new IllegalArgumentException("instanceId 必填");
        }
        if (queueCapacity <= 0 || batchSize <= 0 || flushIntervalMillis <= 0) {
            throw new IllegalArgumentException("队列容量/批大小/flush 间隔必须为正");
        }
        this.instanceId = instanceId;
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<Item>(queueCapacity);
        this.batchSize = batchSize;
        this.flushIntervalMillis = flushIntervalMillis;
    }

    public void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "z-observer-reporter");
        // 守护线程 + 可中断：观测线程不该拖住进程停机
        worker.setDaemon(true);
        worker.start();
    }

    /** 停止并把队列里剩下的数据冲出去。返回冲刷后仍因队列满而丢弃的条数。 */
    public long stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return drainOnce();
    }

    /** 埋点侧入口：只入队，不阻塞、不抛异常。 */
    @Override
    public void accept(LogEvent event) {
        offer(new Item(event));
    }

    @Override
    public void accept(SpanData span) {
        offer(new Item(span));
    }

    private void offer(Item item) {
        if (!running && queue.remainingCapacity() == 0) {
            dropped.incrementAndGet();
            return;
        }
        if (!queue.offer(item)) {
            dropped.incrementAndGet();
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long sentCount() {
        return sent.get();
    }

    public long sendFailureCount() {
        return sendFailures.get();
    }

    public int queuedCount() {
        return queue.size();
    }

    private void loop() {
        while (running) {
            try {
                // 队列空时也定期醒来：flush 间隔是"哪怕只有一条也要按时发"的上限，
                // 完全靠 poll 超时的话空转 CPU，完全不等待的话低流量下数据会一直压着。
                // poll 出来的这一条必须自己处理掉 —— 丢掉它等于每次唤醒白扔一条日志。
                Item head = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
                if (head != null) {
                    dispatch(Collections.singletonList(head));
                }
                drainOnce();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        drainOnce();
    }

    /** 把当前队列里的数据按类型分批发出去，发送失败计入失败数而不是重试。 */
    long drainOnce() {
        while (true) {
            List<Item> items = new ArrayList<Item>(batchSize);
            queue.drainTo(items, batchSize);
            if (items.isEmpty()) {
                return 0;
            }
            dispatch(items);
        }
    }

    private void dispatch(List<Item> items) {
        List<LogEvent> logs = new ArrayList<LogEvent>(items.size());
        List<SpanData> spans = new ArrayList<SpanData>(items.size());
        for (Item it : items) {
            if (it.kind == ItemKind.LOG) {
                logs.add(it.log);
            } else {
                spans.add(it.span);
            }
        }
        if (!logs.isEmpty()) {
            try {
                delegate.send(LogBatch.of(instanceId, logs));
                sent.addAndGet(logs.size());
            } catch (RuntimeException e) {
                // 收端挂了不能重试到底：重试会把上报线程堵死，而数据在队列里等
                // 下一轮同样会失败。计数留痕，由告警来决定要不要放大 buffer。
                sendFailures.addAndGet(logs.size());
            }
        }
        if (!spans.isEmpty()) {
            try {
                delegate.send(SpanBatch.of(instanceId, spans));
                sent.addAndGet(spans.size());
            } catch (RuntimeException e) {
                sendFailures.addAndGet(spans.size());
            }
        }
    }
}