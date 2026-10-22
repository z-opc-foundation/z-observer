package com.zifang.z.observer.admin.collect;

import com.zifang.z.observer.admin.store.LogStore;
import com.zifang.z.observer.admin.store.TraceStore;
import com.zifang.z.observer.core.model.LogBatch;
import com.zifang.z.observer.core.model.LogEvent;
import com.zifang.z.observer.core.protocol.CollectorAuth;
import com.zifang.z.observer.core.protocol.FrameHeader;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集口的**客户端鉴权**闸门。
 *
 * <p>要证明的是三件事：
 * <ol>
 *   <li>配了共享密钥时，<b>没带密钥的客户端进不来</b>（这是本卡第 4 项的核心）；</li>
 *   <li>带了<b>正确</b>密钥的客户端照常能投递（闸门不是把功能也关掉了）；</li>
 *   <li>密钥<b>错</b>的客户端进不来。</li>
 * </ol>
 *
 * <p>用真的 socket + 真的 SDK 发送器（{@code NettyEventSink}），不用 mock 传输层 ——
 * 顺序问题（鉴权帧必须是第一帧）只有真 socket 才现形。
 */
class CollectorAuthTest {

    private static final String SECRET = "s3cr3t-collector-token";

    private CollectorServer server;
    private EventLoopGroup clientGroup;
    private FrameDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.stop();
        }
    }

    private int startCollector(String secret) throws Exception {
        LogStore logStore = new LogStore();
        TraceStore traceStore = new TraceStore();
        HeartbeatTracker heartbeats = new HeartbeatTracker();
        dispatcher = new FrameDispatcher(logStore, traceStore, heartbeats);
        server = new CollectorServer(0, dispatcher, FrameHeader.MAX_BODY_LENGTH,
                secret == null ? null : secret.getBytes("UTF-8"));
        return server.start();
    }

    private void awaitReceived(final long expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (dispatcher.receivedCount() >= expected) {
                Thread.sleep(100);
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待批次超时: received=" + dispatcher.receivedCount());
    }

    private void sendOneBatch(int port, String token) {
        clientGroup = new NioEventLoopGroup(1);
        com.zifang.z.observer.starter.transport.NettyEventSink sink =
                new com.zifang.z.observer.starter.transport.NettyEventSink(
                        "127.0.0.1", port, clientGroup, token);
        sink.send(LogBatch.of("inst-1", Collections.singletonList(
                LogEvent.builder()
                        .timestampMillis(System.currentTimeMillis())
                        .level("ERROR")
                        .logger("com.demo.OrderService")
                        .thread("http-nio-1")
                        .message("鉴权测试")
                        .build())));
        sink.close();
    }

    @Test
    void 没带密钥的客户端进不来() throws Exception {
        int port = startCollector(SECRET);
        sendOneBatch(port, null);
        // 给收端一点时间把（不该收到的）帧处理掉
        Thread.sleep(500);
        assertThat(dispatcher.receivedCount())
                .as("未鉴权的客户端不该让收端收到任何帧")
                .isZero();
    }

    @Test
    void 密钥错的客户端进不来() throws Exception {
        int port = startCollector(SECRET);
        sendOneBatch(port, "wrong-token");
        Thread.sleep(500);
        assertThat(dispatcher.receivedCount())
                .as("密钥不对的客户端不该让收端收到任何帧")
                .isZero();
    }

    @Test
    void 带对密钥的客户端照常能投递() throws Exception {
        int port = startCollector(SECRET);
        sendOneBatch(port, SECRET);
        awaitReceived(1);
        assertThat(dispatcher.rejectedCount()).isZero();
    }

    @Test
    void 握手载荷的构造与校验自洽() {
        byte[] body = CollectorAuth.buildAuthBody(SECRET.getBytes());
        assertThat(CollectorAuth.verify(SECRET.getBytes(), body)).isTrue();
        assertThat(CollectorAuth.verify("other".getBytes(), body)).isFalse();
        // 载荷里不得出现密钥明文
        assertThat(new String(body)).doesNotContain(SECRET);
        // 两次握手的 nonce 必须不同（否则抓包可重放）
        assertThat(CollectorAuth.nonceOf(body))
                .isNotEqualTo(CollectorAuth.nonceOf(CollectorAuth.buildAuthBody(SECRET.getBytes())));
    }
}
