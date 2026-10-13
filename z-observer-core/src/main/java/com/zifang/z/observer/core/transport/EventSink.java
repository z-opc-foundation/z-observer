package com.zifang.z.observer.core.transport;

import com.zifang.z.observer.core.model.LogBatch;
import com.zifang.z.observer.core.model.SpanBatch;

/**
 * 批次去向。SDK 侧只管把批交出去，不关心走 Netty、落文件还是直接进测试断言。
 *
 * <p>{@link #send} 抛异常时调用方会<b>计入失败数</b>而不是静默吞掉 ——
 * 静默丢数据正是 Prometheus remote-write 被业界批评的那件事，这里不重复这个错误。
 */
public interface EventSink {

    void send(LogBatch batch);

    void send(SpanBatch batch);
}