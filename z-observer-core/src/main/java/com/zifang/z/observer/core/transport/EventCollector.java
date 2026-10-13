package com.zifang.z.observer.core.transport;

import com.zifang.z.observer.core.model.LogEvent;
import com.zifang.z.observer.core.model.SpanData;

/**
 * 埋点侧入口：单条事件入队。实现（{@link BatchingReporter}）负责批量与背压。
 *
 * <p>与 {@link EventSink} 分开：前者是"谁产生、往哪塞"（Appender / Tracer 调用），
 * 后者是"批次往哪送"（Netty / 文件 / 测试断言）。两者混成一个接口会让测试收集器
 * 不得不实现用不上的半边，Appender 也得被迫走批次语义。
 */
public interface EventCollector {

    void accept(LogEvent event);

    void accept(SpanData span);
}