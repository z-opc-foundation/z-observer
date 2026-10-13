package com.zifang.z.observer.admin.config;

import com.zifang.z.observer.admin.notify.AlertDispatcher;
import com.zifang.z.observer.admin.notify.MessageChannel;
import com.zifang.z.observer.admin.rule.Alert;
import com.zifang.z.observer.admin.rule.AlertState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道接入缝。
 *
 * <p>钉钉/短信这些真渠道的凭据要走 z-mist，而 z-mist-core 的 {@code @MapperScan}
 * 绑死 {@code sqlSessionFactoryCtc}，把它拖成硬依赖会让收端在没有 ctc 数据源的
 * 环境直接起不来。所以收端只认 {@link MessageChannel} 接口，由宿主把渠道装成
 * bean 递进来 —— 那一层必须真的走得通，否则「凭据不进 z-observer」这个设计
 * 就只是把事情推给了别人而没人验证过。
 */
@SpringBootTest(classes = NotificationChannelWiringTest.TestApp.class, properties = {
        "z.observer.collector-port=0",
        "z.observer.prometheus-url=http://127.0.0.1:1",
        "z.observer.scrape-interval-millis=3600000",
        "z.observer.evaluate-interval-millis=3600000",
        "z.observer.scrape-urls=http://127.0.0.1:1/metrics"
})
class NotificationChannelWiringTest {

    static final List<String> DELIVERED = Collections.synchronizedList(new ArrayList<String>());

    /** 假装是钉钉渠道：凭据从别处取，本仓不留明文。 */
    static final class DingTalkLikeChannel implements MessageChannel {
        @Override
        public String name() {
            return "dingtalk";
        }

        @Override
        public void send(String title, String body) {
            DELIVERED.add(title);
        }
    }

    @SpringBootApplication
    @Import(ObserverAdminConfiguration.class)
    static class TestApp {
        @Bean
        public MessageChannel dingTalkLikeChannel() {
            return new DingTalkLikeChannel();
        }
    }

    @Autowired
    private AlertDispatcher dispatcher;

    @Autowired
    private org.springframework.context.ApplicationContext ctx;

    @Test
    void 宿主递进来的渠道真的进了扇出() {
        DELIVERED.clear();
        Map<String, List<MessageChannel>> channels = ctxBean("notificationChannels");
        List<MessageChannel> all = channels.get("*");

        assertThat(all).isNotEmpty();
        assertThat(all).anyMatch(c -> "dingtalk".equals(c.name()));
        // 日志兜底渠道同时还在：真渠道挂了，告警不至于整条消失
        assertThat(all).anyMatch(c -> "log".equals(c.name()));
    }

    @Test
    void 宿主渠道能收到告警通知() {
        DELIVERED.clear();
        Alert alert = new Alert("gw_down", "网关失联", "critical",
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                AlertState.FIRING, 1000L, 2000L, null);

        dispatcher.notifyFiring(alert, null);

        assertThat(DELIVERED).hasSize(1);
        assertThat(DELIVERED.get(0)).contains("网关失联");
        // sentCount 数的是「渠道 × 消息」，不是消息条数：日志兜底 + 宿主渠道各算一次
        assertThat(dispatcher.sentCount()).isEqualTo(2L);
        assertThat(dispatcher.failedCount()).isZero();
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<MessageChannel>> ctxBean(String name) {
        return (Map<String, List<MessageChannel>>) ctx.getBean(name);
    }
}
