package com.zifang.z.observer.admin.collect;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 收端 Netty 服务：接 SDK 的上报连接。
 *
 * <p><b>线程全部 daemon 且有名字。</b> 收端进程里这个服务不该阻止退出，也不该在
 * 线程 dump 里留下一堆 `pool-3-thread-7` 这种查不出是谁的线程。
 *
 * <p><b>解码异常一律断连。</b> 字节流已经不可信时，继续在这条连接上读只会把错误
 * 传播到后面的每一帧。断连后 SDK 侧会懒重建，这条连接上的损失仅限于此。
 */
public final class CollectorServer {

    private static final int BACKLOG = 1024;

    private final int port;
    private final FrameDispatcher dispatcher;
    private final int maxFrameBytes;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel serverChannel;
    private final AtomicLong droppedConnections = new AtomicLong();

    public CollectorServer(int port, FrameDispatcher dispatcher, int maxFrameBytes) {
        // 0 是合法的：交给系统分配一个空闲端口。禁掉它会让「本地起一个收端试一下」
        // 和测试都得先去抢一个确定空闲的端口号，那才是更容易出错的做法。
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("端口非法: " + port);
        }
        if (dispatcher == null) {
            throw new IllegalArgumentException("dispatcher 必填");
        }
        this.port = port;
        this.dispatcher = dispatcher;
        this.maxFrameBytes = maxFrameBytes;
    }

    /** 绑定并开始服务，返回实际绑定的端口（port 传 0 时由系统分配，测试用得上）。 */
    public int start() throws InterruptedException {
        boss = new NioEventLoopGroup(1, namedFactory("z-observer-collector-boss"));
        worker = new NioEventLoopGroup(0, namedFactory("z-observer-collector-io"));
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, BACKLOG)
                .childOption(ChannelOption.TCP_NODELAY, true)
                // 长连接 + 小帧，SO_KEEPALIVE 开着：SDK 所在机器被拔电时，
                // 半开连接会挂到系统超时（可达数小时），收端一直以为它活着。
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new FrameDecoder(maxFrameBytes));
                        ch.pipeline().addLast(new DispatchHandler(dispatcher()));
                    }
                });
        serverChannel = b.bind(port).sync().channel();
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly(3, TimeUnit.SECONDS);
            serverChannel = null;
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            boss = null;
        }
        if (worker != null) {
            worker.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            worker = null;
        }
    }

    FrameDispatcher dispatcher() {
        return dispatcher;
    }

    public long droppedConnectionCount() {
        return droppedConnections.get();
    }

    void countDroppedConnection() {
        droppedConnections.incrementAndGet();
    }

    private static DefaultThreadFactory namedFactory(final String prefix) {
        final AtomicInteger seq = new AtomicInteger();
        return new DefaultThreadFactory(prefix) {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = super.newThread(r);
                t.setName(prefix + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    /** 帧分派处理器。异常一律上报并断连，不在这里吞。 */
    private final class DispatchHandler extends SimpleChannelInboundHandler<byte[]> {
        private final FrameDispatcher target;

        DispatchHandler(FrameDispatcher target) {
            this.target = target;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, byte[] raw) {
            target.dispatch(raw);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            countDroppedConnection();
            ctx.close();
        }
    }
}
