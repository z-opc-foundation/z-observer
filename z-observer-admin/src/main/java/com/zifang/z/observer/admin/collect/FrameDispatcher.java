package com.zifang.z.observer.admin.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.observer.admin.store.LogStore;
import com.zifang.z.observer.admin.store.TraceStore;
import com.zifang.z.observer.core.model.LogBatch;
import com.zifang.z.observer.core.model.SpanBatch;
import com.zifang.z.observer.core.protocol.Frame;
import com.zifang.z.observer.core.protocol.FrameCodec;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 收端分派：解码一帧、按 type 落进对应的存储。
 *
 * <p><b>坏帧计数而不是静默丢。</b> 协议不兼容、SDK 版本错位、时钟漂移导致的坏帧，
 * 全都表现为「收端数据比预期少」，而少在哪一步只有计数能说清。
 *
 * <p><b>单帧失败不拖垮连接。</b> 一帧解码失败只丢这一帧并计数；只有分帧层出错
 * （magic 不符、长度越界，那已经不是「某一帧有问题」而是「字节流不可信」）
 * 才断连 —— 断连由 {@link FrameDecoder} 抛异常触发。
 */
public final class FrameDispatcher {

    private final LogStore logStore;
    private final TraceStore traceStore;
    private final ObjectMapper mapper;
    private final HeartbeatTracker heartbeats;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong logEvents = new AtomicLong();
    private final AtomicLong spans = new AtomicLong();

    public FrameDispatcher(LogStore logStore, TraceStore traceStore, HeartbeatTracker heartbeats) {
        this(logStore, traceStore, heartbeats, new ObjectMapper());
    }

    public FrameDispatcher(LogStore logStore, TraceStore traceStore,
                           HeartbeatTracker heartbeats, ObjectMapper mapper) {
        if (logStore == null || traceStore == null || heartbeats == null) {
            throw new IllegalArgumentException("存储与心跳不能为 null");
        }
        this.logStore = logStore;
        this.traceStore = traceStore;
        this.heartbeats = heartbeats;
        this.mapper = mapper;
    }

    /** 处理一帧（整帧字节，含头）。返回是否成功解码。 */
    public boolean dispatch(byte[] raw) {
        received.incrementAndGet();
        Frame frame;
        try {
            frame = FrameCodec.decode(raw);
        } catch (RuntimeException e) {
            rejected.incrementAndGet();
            return false;
        }
        try {
            switch (frame.type()) {
                case LOG_BATCH:
                    logStore.appendAll(mapper.readValue(frame.body(), LogBatch.class).events());
                    logEvents.incrementAndGet();
                    return true;
                case SPAN_BATCH:
                    traceStore.appendAll(mapper.readValue(frame.body(), SpanBatch.class).spans());
                    spans.incrementAndGet();
                    return true;
                case HEARTBEAT:
                    heartbeats.beat(frame.body());
                    return true;
                case METRIC_BATCH:
                    // push 边车（D1）的指标批次。当前实现只认帧不落库：
                    // 时序存储已定走 Prometheus，指标落库会变成第二份真值。
                    rejected.incrementAndGet();
                    return false;
                default:
                    rejected.incrementAndGet();
                    return false;
            }
        } catch (Exception e) {
            // body 结构对不上（两侧版本错位）也算坏帧，同样计数。
            rejected.incrementAndGet();
            return false;
        }
    }

    public long receivedCount() {
        return received.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public long logBatchCount() {
        return logEvents.get();
    }

    public long spanBatchCount() {
        return spans.get();
    }
}
