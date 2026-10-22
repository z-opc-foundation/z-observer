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
import com.zifang.z.observer.admin.notify.resilient.NotificationSpool;
import com.zifang.z.observer.admin.notify.resilient.ResilientMessageChannel;
import com.zifang.z.observer.admin.notify.zmsg.HttpMistCredentialProvider;
import com.zifang.z.observer.admin.notify.zmsg.MistCredentialProvider;
import com.zifang.z.observer.admin.notify.zmsg.ZMsgMessageChannel;
import com.zifang.z.observer.admin.rule.RuleEngine;
import com.zifang.z.observer.admin.rule.Rule;
import com.zifang.z.observer.admin.scrape.ScrapeLoop;
import com.zifang.z.observer.admin.security.SecretSource;
import com.zifang.z.observer.admin.scrape.Scraper;
import com.zifang.z.observer.admin.scrape.ScrapeTarget;
import com.zifang.z.observer.admin.scrape.StaticTargetSource;
import com.zifang.z.observer.admin.scrape.TargetRegistry;
import com.zifang.z.observer.admin.scrape.TargetSource;
import com.zifang.z.observer.admin.scrape.ZConfigNamingTargetSource;
import com.zifang.z.config.client.naming.ZNamingService;
import com.zifang.z.observer.admin.store.LogStore;
import com.zifang.z.observer.admin.store.TraceStore;
import com.zifang.z.observer.core.protocol.FrameHeader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
// 配置类自己声明依赖的 properties：否则单独 @Import 这个类时装配不起来，
// 「收端能不能起」就得靠整应用启动才发现
@EnableConfigurationProperties(ObserverAdminProperties.class)
public class ObserverAdminConfiguration {

    private static final Logger LOG = LogManager.getLogger(ObserverAdminConfiguration.class);

    private CollectorServer collectorServer;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService notifyScheduler;
    private final List<ScheduledExecutorService> scrapeSchedulers =
            new ArrayList<ScheduledExecutorService>();

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
        // 采集口的客户端鉴权：共享密钥只从环境变量 / Secret 取（不进配置类、不进仓）。
        // ⚠ 缺密钥 ⇒ **启动失败**，不是「先不鉴权地跑起来」：
        //   不鉴权的采集口 = 任何 LAN 内主机都能往生产日志/链路库注入帧
        //   （既是日志注入面，也是存储耗尽面）。
        byte[] secret = SecretSource.bytesFromEnv(SecretSource.COLLECTOR_AUTH_TOKEN_ENV);
        if (secret == null) {
            throw new IllegalStateException(
                    "采集口客户端鉴权已启用但没配共享密钥：环境变量 "
                            + SecretSource.COLLECTOR_AUTH_TOKEN_ENV
                            + " 必须有值（生产从 Secret 注入）。"
                            + "拒绝以「任何主机都能投递帧」的姿态启动");
        }
        collectorServer = new CollectorServer(props.getCollectorPort(), dispatcher,
                FrameHeader.MAX_BODY_LENGTH, secret);
        int bound = collectorServer.start();
        LOG.info("z-observer 收端已监听 {}:{}（采集口客户端鉴权已启用）",
                props.getCollectorPort(), bound);
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
     * 采集 target 的发现源（D1）。
     *
     * <p>环境里有 z-config 客户端 bean 就订阅 naming，没有就退回配置里手写的静态列表 ——
     * M1 起步阶段 z-config 未必先就绪，但采集与 absent 闭环得能立刻跑通。
     */
    @Bean
    public TargetSource targetSource(ObjectProvider<ZNamingService> naming,
                                     ObserverAdminProperties props) {
        ZNamingService client = naming.getIfAvailable();
        if (client != null && !props.getNamingServices().isEmpty()) {
            return new ZConfigNamingTargetSource(client, props.getNamingServices(),
                    props.getMetricsPort());
        }
        if (props.getScrapeUrls().isEmpty()) {
            LOG.warn("既没有 z-config 客户端也没配 scrape-urls，采集层空转");
            return new StaticTargetSource(Collections.singletonList(
                    new ScrapeTarget("disabled", "http://127.0.0.1:1/metrics")));
        }
        return new StaticTargetSource(toTargets(props.getScrapeUrls()));
    }

    private static List<ScrapeTarget> toTargets(List<String> urls) {
        List<ScrapeTarget> out = new ArrayList<ScrapeTarget>();
        for (String url : urls) {
            out.add(new ScrapeTarget(url, url));
        }
        return out;
    }

    @Bean
    public TargetRegistry targetRegistry() {
        return new TargetRegistry();
    }

    @Bean
    public ScrapeLoop scrapeLoop(TargetSource source, TargetRegistry registry,
                                 Scraper scraper, ObserverAdminProperties props)
            throws InterruptedException {
        ScrapeLoop loop = new ScrapeLoop(source, registry, scraper);
        long interval = props.getScrapeIntervalMillis();
        ScheduledExecutorService scrapeScheduler =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread t = new Thread(runnable, "z-observer-scraper");
                    t.setDaemon(true);
                    return t;
                });
        scrapeScheduler.scheduleWithFixedDelay(() -> {
            try {
                ScrapeLoop.Round round = loop.run(System.currentTimeMillis());
                if (round.okCount() + round.failCount() > 0) {
                    LOG.debug("采集完成: 成功 {} 失败 {} 新消失 {}",
                            Integer.valueOf(round.okCount()), Integer.valueOf(round.failCount()),
                            Integer.valueOf(round.newlyVanished().size()));
                }
            } catch (RuntimeException e) {
                // 采集循环必须活着。单个 target 的失败已由 ScrapeLoop 隔离，
                // 这里兜的是循环本身的意外。
                LOG.warn("采集轮次异常: {}", e.getMessage());
            }
        }, 0L, interval, TimeUnit.MILLISECONDS);
        scrapeSchedulers.add(scrapeScheduler);
        return loop;
    }

    /**
     * 通知渠道。
     *
     * <p>容器里每一个 {@link MessageChannel} bean 都会被收进通配的「*」组，
     * 外加一个写日志的兜底渠道。日志渠道不是凑合实现：真渠道没配好时告警至少还
     * 留在收端日志里，不会因为「没渠道」整条消失；真渠道接上后它仍留着。
     *
     * <p>刻意<b>不</b>在这里 new 具体渠道：钉钉/短信的凭据要走 z-mist，
     * 把 z-msg / z-mist 拖进来当硬依赖，会让收端在没有 MyBatis、没有 ctc 数据源
     * 的环境里直接起不来（z-mist-core 的 {@code @MapperScan} 绑死
     * {@code sqlSessionFactoryCtc}）。渠道由宿主应用装配成 bean 递进来，
     * z-observer 只认接口 —— 见 README「告警」一节。
     *
     * <p><b>z-msg 渠道例外，且仍然不引它的 jar。</b> 它是跨进程 HTTP 的客户端
     * （需求 009 §1.3 的红线：z-msg 与其它进程只能跨进程 HTTP），
     * 凭据运行时从 z-mist 取，所以收端依旧零凭据依赖。
     * 它外面套一层 {@link ResilientMessageChannel}：z-msg 不可用时重试、
     * 再不行落盘待发、后台补发 —— 绝不静默丢。
     */
    @Bean
    public Map<String, List<MessageChannel>> notificationChannels(
            ObjectProvider<MessageChannel> declared,
            ObserverAdminProperties props) {
        List<MessageChannel> all = new ArrayList<MessageChannel>();
        all.add(new LoggingChannel());
        declared.orderedStream().forEach(all::add);

        ResilientMessageChannel zmsg = buildZMsgChannel(props);
        if (zmsg != null) {
            all.add(zmsg);
        }

        Map<String, List<MessageChannel>> channels = new LinkedHashMap<String, List<MessageChannel>>();
        channels.put("*", all);
        return channels;
    }

    /**
     * 装配 z-msg 渠道（跨进程 HTTP + 韧性包装）。
     *
     * <p>没配 {@code z.observer.notify.z-msg.base-url} 就不装 —— 收端在
     * 「还没接渠道」的环境里必须照常起得来，不能因为少一个配置就起不来。
     */
    private ResilientMessageChannel buildZMsgChannel(ObserverAdminProperties props) {
        ObserverAdminProperties.Notify notify = props.getNotify();
        if (!notify.isEnabled()) {
            LOG.info("通知出口未启用（z.observer.notify.enabled=false），只用日志兜底渠道");
            return null;
        }
        ObserverAdminProperties.ZMsg zmsg = notify.getZmsg();
        if (zmsg.getBaseUrl() == null || zmsg.getBaseUrl().trim().isEmpty()) {
            LOG.warn("通知出口已启用但没配 z-msg 地址，只用日志兜底渠道");
            return null;
        }
        MistCredentialProvider credentialProvider = null;
        ObserverAdminProperties.Mist mist = notify.getMist();
        if (zmsg.getReceiverRef() != null && !zmsg.getReceiverRef().trim().isEmpty()) {
            if (mist.getBaseUrl() == null || mist.getBaseUrl().trim().isEmpty()) {
                // 有引用却没配 z-mist：这条通知发不出去，但不能让收端起不来。
                // 通道照样装上，发送时会因为取不到凭据而失败 → 落盘待发 → 有人看得见。
                LOG.error("配了收件人引用却没配 z-mist 地址，通知会取不到凭据（将落盘待发）");
            } else {
                credentialProvider = new HttpMistCredentialProvider(mist.getBaseUrl(),
                        mist.getConnectTimeoutMillis(), mist.getReadTimeoutMillis(),
                        mist.getGroup(), mist.getNamespace());
            }
        }
        MessageChannel zmsgChannel = new ZMsgMessageChannel(zmsg.getBaseUrl(), zmsg.getSendPath(),
                zmsg.getChannel(), zmsg.getBizType(), zmsg.getReceiverRef(),
                zmsg.getReceiverDirect(), credentialProvider,
                zmsg.getConnectTimeoutMillis(), zmsg.getReadTimeoutMillis());

        ObserverAdminProperties.Resilient resilient = notify.getResilient();
        NotificationSpool spool = new NotificationSpool(new java.io.File(resilient.getSpoolDir()));
        ResilientMessageChannel wrapped = new ResilientMessageChannel(zmsgChannel,
                resilient.getMaxAttempts(), resilient.getBackoffMillis(), spool,
                resilient.getMaxReplayAttempts());

        startReplayTask(wrapped, resilient.getReplayIntervalMillis());
        LOG.info("通知出口已装配: 通道={} 目标={} 收件人来源={} 待发目录={}",
                zmsg.getChannel(), zmsg.getBaseUrl(),
                (zmsg.getReceiverRef() == null || zmsg.getReceiverRef().isEmpty())
                        ? "直填" : "z-mist 引用",
                resilient.getSpoolDir());
        return wrapped;
    }

    /** 后台补发：定时把待发目录里的通知重投一遍。 */
    private void startReplayTask(ResilientMessageChannel channel, long intervalMillis) {
        if (intervalMillis <= 0) {
            return;
        }
        notifyScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "z-observer-notify-replay");
            t.setDaemon(true);
            return t;
        });
        notifyScheduler.scheduleWithFixedDelay(() -> {
            try {
                int delivered = channel.replayOnce();
                if (delivered > 0) {
                    LOG.info("待发通知补发 {} 条", Integer.valueOf(delivered));
                }
            } catch (RuntimeException e) {
                LOG.warn("补发轮次异常: {}", e.getMessage());
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Bean
    public AlertDispatcher alertDispatcher(Map<String, List<MessageChannel>> channels) {
        return new AlertDispatcher(channels);
    }

    @Bean
    public RuleEngine ruleEngine(MetricQuery metricQuery, AlertDispatcher dispatcher,
                                 ObserverAdminProperties props) {
        RuleEngine engine = new RuleEngine(metricQuery, dispatcher);
        // 声明式规则在这里落地。没有这一步，评估循环每 15 秒醒一次、规则集恒空，
        // 告警通路等于不存在 —— 而且不报任何错，最难发现的一种坏法。
        for (RuleSpec spec : props.getRules()) {
            try {
                engine.registerRule(spec.toRule());
            } catch (RuntimeException e) {
                // 一条规则写错不能拖垮整个收端：剩下的规则照评，
                // 但必须喊出来，否则值班的人会以为「配了就是生效了」。
                LOG.error("规则 {} 装配失败，已跳过: {}", spec.getId(), e.getMessage());
            }
        }
        if (props.getRules().isEmpty()) {
            LOG.warn("一条告警规则都没配，评估循环空转：进程挂掉不会有人收到通知");
        } else {
            LOG.info("已装配告警规则 {} 条: {}", Integer.valueOf(props.getRules().size()),
                    engine.rules());
        }
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
        if (notifyScheduler != null) {
            notifyScheduler.shutdownNow();
        }
        for (ScheduledExecutorService s : scrapeSchedulers) {
            s.shutdownNow();
        }
    }
}
