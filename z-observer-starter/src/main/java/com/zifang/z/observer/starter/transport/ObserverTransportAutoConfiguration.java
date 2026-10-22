package com.zifang.z.observer.starter.transport;

import com.zifang.z.observer.core.trace.Tracer;
import com.zifang.z.observer.core.transport.BatchingReporter;
import com.zifang.z.observer.core.transport.EventCollector;
import com.zifang.z.observer.core.transport.EventSink;
import com.zifang.z.observer.starter.log.ObserverLogAppender;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 上报通道装配：EventCollector（入队）、Tracer、log4j2 Appender。
 *
 * <p>整块只在<b>显式开了 transport 且配了 host</b> 时才装配。这不是偷懒，是必需的：
 * 消费方加依赖是为了埋点与指标暴露，很可能压根不需要往收端送日志和链路。
 * 若无条件装配，本地开发和单测就被迫先起一个收端；更糟的是 host 漏配时启动直接失败，
 * 故障现场跟业务毫无关系。
 */
@Configuration
@EnableConfigurationProperties(ObserverTransportProperties.class)
@ConditionalOnProperty(prefix = "z.observer.transport", name = "enabled", havingValue = "true")
public class ObserverTransportAutoConfiguration {

    @Bean(destroyMethod = "shutdownGracefully")
    @ConditionalOnMissingBean
    public EventLoopGroup zObserverEventLoopGroup() {
        final AtomicInteger n = new AtomicInteger();
        return new NioEventLoopGroup(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "z-observer-netty-" + n.incrementAndGet());
                // 守护线程：观测通道不该拖住进程停机
                t.setDaemon(true);
                return t;
            }
        });
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public NettyEventSink zObserverEventSink(ObserverTransportProperties properties,
                                              EventLoopGroup group) {
        return new NettyEventSink(requireHost(properties), properties.getPort(), group,
                properties.getAuthToken());
    }

    @Bean
    @ConditionalOnMissingBean
    public BatchingReporter zObserverReporter(ObserverTransportProperties properties,
                                               EventSink sink) {
        BatchingReporter reporter = new BatchingReporter(resolveInstanceId(properties),
                sink, properties.getQueueCapacity(), properties.getBatchSize(),
                properties.getFlushIntervalMillis());
        reporter.start();
        return reporter;
    }

    @Bean
    @ConditionalOnMissingBean
    public Tracer zObserverTracer(EventCollector collector) {
        return new Tracer(collector);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObserverLogAppender zObserverLogAppender(EventCollector collector) {
        return ObserverLogAppender.newBuilder()
                .setName("z-observer")
                .setCollector(collector)
                .build();
    }

    @Bean
    public CollectorLifecycle zObserverCollectorLifecycle(BatchingReporter reporter) {
        return new CollectorLifecycle(reporter);
    }

    /**
     * 停机时冲刷剩余数据。挂成 DisposableBean 而不是靠 destroyMethod：
     * 上报器是 daemon 线程，进程退出时不会等它，冲刷必须发生在容器关闭钩子里。
     */
    public static class CollectorLifecycle implements DisposableBean {
        private final BatchingReporter reporter;

        public CollectorLifecycle(BatchingReporter reporter) {
            this.reporter = reporter;
        }

        @Override
        public void destroy() {
            reporter.stop();
        }
    }

    private static String requireHost(ObserverTransportProperties properties) {
        if (properties.getHost() == null || properties.getHost().isEmpty()) {
            // 开了 transport 却没给 host：继续跑的话队列会一路堆到满然后开始丢数据，
            // 而现象只是"日志没上来"。宁可启动失败并在配置里指名道姓。
            throw new IllegalStateException(
                    "z.observer.transport.enabled=true 时必须配置 z.observer.transport.host");
        }
        return properties.getHost();
    }

    static String resolveInstanceId(ObserverTransportProperties properties) {
        if (properties.getInstanceId() != null && !properties.getInstanceId().isEmpty()) {
            return properties.getInstanceId();
        }
        // 主机名 + 端口：同机多实例时端口是唯一能区分它们的字段
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return host + ":" + properties.getPort();
    }
}