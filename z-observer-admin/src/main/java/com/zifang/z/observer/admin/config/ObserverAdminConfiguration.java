package com.zifang.z.observer.admin.config;

import com.zifang.z.observer.admin.ObserverAdminProperties;
import com.zifang.z.observer.admin.collect.CollectorServer;
import com.zifang.z.observer.admin.collect.FrameDispatcher;
import com.zifang.z.observer.admin.collect.HeartbeatTracker;
import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.PrometheusHttpClient;
import com.zifang.z.observer.admin.notify.AlertDispatcher;
import com.zifang.z.observer.admin.notify.LoggingChannel;
import com.zifang.z.observer.admin.notify.MessageChannel;
import com.zifang.z.observer.admin.rule.RuleEngine;
import com.zifang.z.observer.admin.scrape.Scraper;
import com.zifang.z.observer.admin.store.LogStore;
import com.zifang.z.observer.admin.store.TraceStore;
import com.zifang.z.observer.core.protocol.FrameHeader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 收端各部件的装配。 */
@Configuration
public class ObserverAdminConfiguration {

    private static final Logger LOG = LogManager.getLogger(ObserverAdminConfiguration.class);

    private CollectorServer collectorServer;
    private ScheduledExecutorService scheduler;

    @Bean
    public LogStore logStore(ObserverAdminProperties props) {
        return new LogStore(props.getLogMaxDocs(), props.getLogRetentionMillis());
    }

    @Bean
    public TraceStore traceStore(ObserverAdminProperties props) {
        return new TraceStore(props.getTraceMaxTraces(), props.getTraceRetentionMillis());
    }

    @Bean
    public HeartbeatTracker heartbeatTracker() {
        return new HeartbeatTracker();
    }

    @Bean
    public FrameDispatcher frameDispatcher(LogStore logStore, TraceStore traceStore,
                                           HeartbeatTracker heartbeats) {
        return new FrameDispatcher(logStore, traceStore, heartbeats);
    }

    /**
     * 收端自己也要被观测。它和被观测方用同一套自研指标口径，
     * 所以收端挂了的时候，接它的那个监控能按同一套规则发现。
     */
    @Bean(destroyMethod = "stop")
    public CollectorServer collectorServer(ObserverAdminProperties props,
                                           FrameDispatcher dispatcher) throws InterruptedException {
        collectorServer = new CollectorServer(props.getCollectorPort(), dispatcher,
                FrameHeader.MAX_BODY_LENGTH);
        int bound = collectorServer.start();
        LOG.info("z-observer 收端已监听 {}:{}", props.getCollectorPort(), bound);
        return collectorServer;
    }

    @Bean
    public MetricQuery metricQuery(ObserverAdminProperties props) {
        return new PrometheusHttpClient(props.getPrometheusUrl());
    }

    @Bean
    public Scraper scraper() {
        return new Scraper();
    }

    /**
     * 通知渠道。
     *
     * <p>默认只挂一个写日志的渠道：真渠道的凭据要走 z-mist，装 z-msg 客户端后
     * 往这个 map 里加 bean 即可。没配真渠道时告警仍会落记录，
     * 不会「以为发出去了」。
     */
    @Bean
    public Map<String, List<MessageChannel>> notificationChannels() {
        Map<String, List<MessageChannel>> channels = new LinkedHashMap<String, List<MessageChannel>>();
        channels.put("*", new ArrayList<MessageChannel>(
                Collections.singletonList(new LoggingChannel())));
        return channels;
    }

    @Bean
    public AlertDispatcher alertDispatcher(Map<String, List<MessageChannel>> channels) {
        return new AlertDispatcher(channels);
    }

    @Bean
    public RuleEngine ruleEngine(MetricQuery metricQuery, AlertDispatcher dispatcher,
                                 ObserverAdminProperties props) {
        RuleEngine engine = new RuleEngine(metricQuery, dispatcher);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "z-observer-rule-evaluator");
            t.setDaemon(true);
            return t;
        });
        long interval = props.getEvaluateIntervalMillis();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                engine.evaluate(System.currentTimeMillis());
            } catch (RuntimeException e) {
                // 评估循环必须活着。引擎内部已按规则隔离异常，这里兜的是引擎外的意外。
                LOG.warn("规则评估轮次异常: {}", e.getMessage());
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        return engine;
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
