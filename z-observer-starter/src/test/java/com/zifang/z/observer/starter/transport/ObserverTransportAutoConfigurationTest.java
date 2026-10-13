package com.zifang.z.observer.starter.transport;

import com.zifang.z.observer.core.trace.Tracer;
import com.zifang.z.observer.core.transport.BatchingReporter;
import com.zifang.z.observer.starter.log.ObserverLogAppender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ObserverTransportAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObserverTransportAutoConfiguration.class));

    @Test
    void 默认不开transport时不装配上报通道() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(BatchingReporter.class);
            assertThat(ctx).doesNotHaveBean(Tracer.class);
            assertThat(ctx).doesNotHaveBean(ObserverLogAppender.class);
        });
    }

    @Test
    void 显式关闭时不装配() {
        runner.withPropertyValues("z.observer.transport.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BatchingReporter.class));
    }

    @Test
    void 开了且配了host时装配齐全() {
        runner.withPropertyValues(
                "z.observer.transport.enabled=true",
                "z.observer.transport.host=127.0.0.1",
                "z.observer.transport.port=14318",
                "z.observer.transport.instance-id=host-a").run(ctx -> {
                    assertThat(ctx).hasSingleBean(BatchingReporter.class);
                    assertThat(ctx).hasSingleBean(Tracer.class);
                    assertThat(ctx).hasSingleBean(ObserverLogAppender.class);
                    assertThat(ctx).hasSingleBean(NettyEventSink.class);
                });
    }

    @Test
    void 开了却没配host时启动失败_而不是静默丢数据() {
        runner.withPropertyValues("z.observer.transport.enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasMessageContaining("transport.host");
        });
    }

    @Test
    void 显式instanceId优先() {
        ObserverTransportProperties p = new ObserverTransportProperties();
        p.setInstanceId("my-id");
        assertThat(ObserverTransportAutoConfiguration.resolveInstanceId(p)).isEqualTo("my-id");
    }

    @Test
    void 没配instanceId时用主机名加端口() {
        ObserverTransportProperties p = new ObserverTransportProperties();
        p.setPort(9999);
        assertThat(ObserverTransportAutoConfiguration.resolveInstanceId(p)).endsWith(":9999");
    }

    @Test
    void 队列与批量参数可配() {
        runner.withPropertyValues(
                "z.observer.transport.enabled=true",
                "z.observer.transport.host=127.0.0.1",
                "z.observer.transport.queue-capacity=64",
                "z.observer.transport.batch-size=8",
                "z.observer.transport.flush-interval-millis=100").run(ctx -> {
                    BatchingReporter r = ctx.getBean(BatchingReporter.class);
                    // 用背压间接验证参数真的进了构造：容量 64 时塞 100 条必有丢弃
                    for (int i = 0; i < 100; i++) {
                        r.accept(com.zifang.z.observer.core.model.LogEvent.builder()
                                .timestampMillis(1).message("m").build());
                    }
                    assertThat(r.droppedCount()).isGreaterThan(0L);
                });
    }
}