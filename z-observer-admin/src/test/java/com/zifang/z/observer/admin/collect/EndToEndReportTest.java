package com.zifang.z.observer.admin.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.observer.admin.store.LogQuery;
import com.zifang.z.observer.admin.store.LogStore;
import com.zifang.z.observer.admin.store.TraceStore;
import com.zifang.z.observer.core.model.LogBatch;
import com.zifang.z.observer.core.model.LogEvent;
import com.zifang.z.observer.core.model.SpanBatch;
import com.zifang.z.observer.core.model.SpanData;
import com.zifang.z.observer.core.protocol.FrameHeader;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：真的起一个收端，用真的 SDK 侧的 {@code NettyEventSink} 打过去。
 *
 * <p>不用 mock 传输层，是因为这条链路上已经出过两次「单测全绿但线上根本发不出去」的问题
 * （模型对 Jackson 不可见、帧长错位）。它们全都只在真 socket 上才现形。
 */
class EndToEndReportTest {

    private LogStore logStore;
    private TraceStore traceStore;
    private HeartbeatTracker heartbeats;
    private FrameDispatcher dispatcher;
    private CollectorServer server;
    private EventLoopGroup clientGroup;

    @AfterEach
    void tearDown() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.stop();
        }
    }

    private int startCollector() throws Exception {
        logStore = new LogStore();
        traceStore = new TraceStore();
        heartbeats = new HeartbeatTracker();
        dispatcher = new FrameDispatcher(logStore, traceStore, heartbeats);
        server = new CollectorServer(0, dispatcher, FrameHeader.MAX_BODY_LENGTH);
        return server.start();
    }

    /** 收端写入是异步的，测试必须等它落库而不是靠 sleep 猜。 */
    private void awaitBatches(final int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (dispatcher.receivedCount() >= expected) {
                Thread.sleep(100);
                if (dispatcher.rejectedCount() == 0) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待批次超时: received=" + dispatcher.receivedCount()
                + " rejected=" + dispatcher.rejectedCount());
    }

    @Test
    void 日志批次能从SDK发到收端并被检索到() throws Exception {
        int port = startCollector();
        clientGroup = new NioEventLoopGroup(1);
        Sink sink = new Sink("127.0.0.1", port);

        sink.send(LogBatch.of("inst-1", Collections.singletonList(
                LogEvent.builder()
                        .timestampMillis(System.currentTimeMillis())
                        .level("ERROR")
                        .logger("com.demo.OrderService")
                        .thread("http-nio-1")
                        .message("订单创建失败 orderId=42")
                        .traceId("trace-abc")
                        .build())));

        awaitBatches(1);

        assertThat(dispatcher.rejectedCount()).isZero();
        assertThat(logStore.size()).isEqualTo(1);
        List<LogEvent> hits = logStore.search(LogQuery.builder().text("失败").build());
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).traceId()).isEqualTo("trace-abc");
        sink.close();
    }

    @Test
    void span批次能过网并组出链路树() throws Exception {
        int port = startCollector();
        clientGroup = new NioEventLoopGroup(1);
        Sink sink = new Sink("127.0.0.1", port);

        long now = System.currentTimeMillis();
        sink.send(SpanBatch.of("inst-1", java.util.Arrays.asList(
                SpanData.builder().traceId("t-1").spanId("s-1").name("GET /order")
                        .startTimeMillis(now).endTimeMillis(now + 100).build(),
                SpanData.builder().traceId("t-1").spanId("s-2").parentSpanId("s-1").name("db")
                        .startTimeMillis(now + 10).endTimeMillis(now + 50).build())));

        awaitBatches(1);

        TraceStore.TraceTree tree = traceStore.trace("t-1");
        assertThat(tree).isNotNull();
        assertThat(tree.spanCount()).isEqualTo(2);
        assertThat(tree.roots().get(0).children()).hasSize(1);
        sink.close();
    }

    @Test
    void 心跳能登记实例() throws Exception {
        int port = startCollector();
        clientGroup = new NioEventLoopGroup(1);
        Sink sink = new Sink("127.0.0.1", port);

        sink.heartbeat("inst-9");

        awaitBatches(1);
        assertThat(heartbeats.isAlive("inst-9")).isTrue();
        sink.close();
    }

    /** 一条连接上连发两帧：收端必须按帧头里的 bodyLength 切，而不是按到达的字节块切。 */
    @Test
    void 一条连接上连发两帧都能切开() throws Exception {
        int port = startCollector();
        clientGroup = new NioEventLoopGroup(1);
        Sink sink = new Sink("127.0.0.1", port);

        sink.send(batch("第一帧"));
        sink.send(batch("第二帧"));

        awaitBatches(2);
        assertThat(logStore.size()).isEqualTo(2);
        assertThat(logStore.search(LogQuery.builder().text("第一帧").build())).hasSize(1);
        assertThat(logStore.search(LogQuery.builder().text("第二帧").build())).hasSize(1);
        sink.close();
    }

    private static LogBatch batch(String message) {
        return LogBatch.of("inst-1", Collections.singletonList(
                LogEvent.builder().timestampMillis(System.currentTimeMillis())
                        .level("INFO").message(message).build()));
    }

    /** 直接复用 SDK 侧的发送器，这样测试钉的就是真实协议实现而不是复刻一遍。 */
    private final class Sink {
        private final com.zifang.z.observer.starter.transport.NettyEventSink delegate;

        Sink(String host, int port) {
            delegate = new com.zifang.z.observer.starter.transport.NettyEventSink(
                    host, port, clientGroup);
        }

        void send(LogBatch b) {
            delegate.send(b);
        }

        void send(SpanBatch b) {
            delegate.send(b);
        }

        void heartbeat(String instanceId) {
            delegate.sendHeartbeat(instanceId);
        }

        void close() {
            delegate.close();
        }
    }
}
