package com.zifang.z.observer.admin.config;

import com.zifang.z.observer.admin.collect.CollectorServer;
import com.zifang.z.observer.admin.collect.FrameDispatcher;
import com.zifang.z.observer.admin.collect.HeartbeatTracker;
import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.notify.AlertDispatcher;
import com.zifang.z.observer.admin.rule.RuleEngine;
import com.zifang.z.observer.admin.scrape.ScrapeLoop;
import com.zifang.z.observer.admin.scrape.StaticTargetSource;
import com.zifang.z.observer.admin.scrape.TargetSource;
import com.zifang.z.observer.admin.store.LogStore;
import com.zifang.z.observer.admin.store.TraceStore;
import com.zifang.z.observer.core.model.LogEvent;
import com.zifang.z.observer.core.protocol.FrameHeader;
import com.zifang.z.observer.core.protocol.FrameType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整容器起一遍。
 *
 * <p>这一层的单测全绿不代表收端能起来：bean 之间的依赖环、缺 bean、
 * 构造器参数名对不上，都是只有真起容器才暴露的问题。端口全给 0，
 * 让 OS 分配，避免并行跑测试时互撞。
 */
@SpringBootTest(classes = ObserverAdminConfigurationTest.TestApp.class,
        properties = {
                "z.observer.collector-port=0",
                "z.observer.prometheus-url=http://127.0.0.1:1",
                "z.observer.scrape-interval-millis=3600000",
                "z.observer.evaluate-interval-millis=3600000",
                // 没 z-config 客户端，靠静态地址走通「发现源不是 naming」这条装配分支
                "z.observer.scrape-urls=http://127.0.0.1:1/metrics"
        })
class ObserverAdminConfigurationTest {

    @SpringBootApplication
    @Import(ObserverAdminConfiguration.class)
    static class TestApp {
    }

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private CollectorServer collector;

    @Autowired
    private ScrapeLoop scrapeLoop;

    @Test
    void 收端能起且各部件齐备() {
        assertThat(ctx.getBean(LogStore.class)).isNotNull();
        assertThat(ctx.getBean(TraceStore.class)).isNotNull();
        assertThat(ctx.getBean(FrameDispatcher.class)).isNotNull();
        assertThat(ctx.getBean(HeartbeatTracker.class)).isNotNull();
        assertThat(ctx.getBean(MetricQuery.class)).isNotNull();
        assertThat(ctx.getBean(RuleEngine.class)).isNotNull();
        assertThat(ctx.getBean(AlertDispatcher.class)).isNotNull();
        assertThat(scrapeLoop).isNotNull();
    }

    @Test
    void 收端真的在接受连接() throws Exception {
        int bound = collector.start();
        assertThat(bound).isGreaterThan(0);
        java.net.Socket s = new java.net.Socket("127.0.0.1", bound);
        s.close();
    }

    @Test
    void 没有naming客户端时退回静态target源() {
        assertThat(ctx.getBean(TargetSource.class)).isInstanceOf(StaticTargetSource.class);
        assertThat(scrapeLoop.registry().live()).hasSize(1);
    }

    @Test
    void 默认只挂log渠道_告警不会因为没渠道而消失() {
        Map<String, ?> channels = ctx.getBean("notificationChannels", Map.class);
        assertThat(channels).containsKey("*");
        AlertDispatcher d = ctx.getBean(AlertDispatcher.class);
        assertThat(d.sentCount()).isZero();
    }

    @Test
    void 帧头常量没被改过() {
        assertThat(FrameHeader.HEADER_LENGTH).isEqualTo(12);
        assertThat(FrameType.LOG_BATCH).isNotNull();
        assertThat(LogEvent.builder().message("x").level("INFO").logger("L")
                .thread("main").timestampMillis(1L).build().message()).isEqualTo("x");
    }
}