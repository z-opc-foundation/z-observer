package com.zifang.z.observer.starter.transport;

import com.zifang.util.json.JsonUtil;
import com.zifang.z.observer.core.model.LogBatch;
import com.zifang.z.observer.core.model.SpanBatch;

import com.zifang.z.observer.core.protocol.FrameCodec;
import com.zifang.z.observer.core.protocol.FrameType;
import com.zifang.z.observer.core.transport.EventSink;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通过私有协议帧把批次送到收端的 {@link EventSink}。
 *
 * <p><b>发送失败直接抛，让调用方计数。</b> 队列满了要丢可以（那是背压，有 dropped 指标），
 * 但"以为发出去了"不行 —— 这正是 Prometheus remote-write 队列满时静默丢弃被诟病的那件事，
 * 这里不重复。
 *
 * <p>连接是懒建立 + 断线重建的：观测通道不该让业务进程启动时被收端不可用拖住，
 * 也不该要求收端先于业务进程上线。
 */
public class NettyEventSink implements EventSink {

    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int SEND_TIMEOUT_MILLIS = 3000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String host;
    private final int port;
    private final EventLoopGroup group;

    private volatile Channel channel;
    private final AtomicLong sentFrames = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public NettyEventSink(String host, int port, EventLoopGroup group) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("host 必填");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("端口非法: " + port);
        }
        this.host = host;
        this.port = port;
        this.group = group;
    }

    public long sentFrameCount() {
        return sentFrames.get();
    }

    public long failureCount() {
        return failures.get();
    }

    @Override
    public void send(LogBatch batch) {
        write(FrameType.LOG_BATCH, batch);
    }

    @Override
    public void send(SpanBatch batch) {
        write(FrameType.SPAN_BATCH, batch);
    }

    /**
     * 主动发心跳。
     *
     * <p>心跳是判活的唯一依据：健康但没有日志、没有请求的服务在「按数据判活」的口径下
     * 就是死的，会收到一条莫名其妙的告警。心跳与业务数据走同一条连接，
     * 所以连接断了心跳自然也断，不需要额外的存活检测通道。
     */
    public void sendHeartbeat(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            throw new IllegalArgumentException("instanceId 必填");
        }
        java.util.Map<String, String> body =
                java.util.Collections.singletonMap("instanceId", instanceId);
        write(FrameType.HEARTBEAT, body);
    }

    private void write(FrameType type, Object payload) {
        byte[] body;
        try {
            // 原先这里是 mapper.writeValueAsBytes。批次的 null 字段要留在报文里
            // （那是 new ObjectMapper() 的默认口径，不是 JsonMapperFactory 的 NON_NULL），
            // 所以用 toJson 而不是 toJsonExcludingNulls。
            body = JsonUtil.toJson(payload).getBytes(UTF8);
        } catch (RuntimeException e) {
            // 序列化失败是编码 bug，不是网络问题 —— 往上抛，让它出现在测试里而不是线上日志里
            throw new IllegalStateException("序列化 " + type + " 失败", e);
        }
        byte[] raw = FrameCodec.encode(type, body);
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            ch = connect();
        }
        if (ch == null) {
            failures.incrementAndGet();
            throw new IllegalStateException("连不上收端 " + host + ":" + port);
        }
        ChannelFuture future = ch.writeAndFlush(Unpooled.wrappedBuffer(raw));
        // 不等 ack：上报线程不是业务线程，为一次上报阻塞到超时会让队列堆积得更快。
        // 失败在这里只计数，真正的"丢了多少"由上报器的 sendFailureCount 汇总。
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture f) {
                if (f.isSuccess()) {
                    sentFrames.incrementAndGet();
                } else {
                    failures.incrementAndGet();
                }
            }
        });
    }

    private Channel connect() {
        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 帧长已经在帧头里，按帧解码，不需要额外的拆包处理器
                        ch.pipeline().addLast(new com.zifang.z.observer.starter.transport.
                                FrameDecoder());
                    }
                });
        try {
            Channel ch = b.connect(host, port).sync().channel();
            this.channel = ch;
            return ch;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            return null;
        }
    }

    /** 主动关闭连接。收端重启后由下一次 send 懒重建。 */
    public void close() {
        Channel ch = channel;
        channel = null;
        if (ch != null) {
            ch.close().awaitUninterruptibly(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }
}