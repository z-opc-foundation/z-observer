package com.zifang.z.observer.admin;

import com.zifang.z.observer.admin.config.RuleSpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * z-observer 收端配置。
 *
 * <p>没有任何凭据字段：通知渠道的密钥一律运行时从 z-mist 取。
 * 配置类里出现 token/webhookSecret 这类字段，几乎必然在某次调试后被提交进 git，
 * 而全仓零明文是硬要求。
 */
@ConfigurationProperties(prefix = "z.observer")
public class ObserverAdminProperties {

    /** 收端监听端口。 */
    private int collectorPort = 14300;

    /** Prometheus 本体地址（D2 过渡方案 B：M1 不自建存储，直读它）。 */
    private String prometheusUrl = "http://127.0.0.1:9090";

    /** 规则评估间隔。 */
    private long evaluateIntervalMillis = 15_000L;

    /** 日志存储条数上限。 */
    private int logMaxDocs = 200_000;

    /** 日志保留时长。 */
    private long logRetentionMillis = 24 * 60 * 60 * 1000L;

    /** 链路条数上限。 */
    private int traceMaxTraces = 20_000;

    /** 链路保留时长。 */
    private long traceRetentionMillis = 2 * 60 * 60 * 1000L;

    /** 声明式告警规则。空列表 = 一条规则都不评估（会打 warn，不静默）。 */
    private List<RuleSpec> rules = new ArrayList<RuleSpec>();

    /** 采集间隔。 */
    private long scrapeIntervalMillis = 15_000L;

    /** 被观测方暴露 /metrics 的端口。 */
    private int metricsPort = 9100;

    /** 订阅 z-config naming 的服务名清单。 */
    private List<String> namingServices = new ArrayList<String>();

    /** 静态采集地址（z-config 未就绪时的过渡配置）。 */
    private List<String> scrapeUrls = new ArrayList<String>();

    /** 通知出口配置。 */
    private Notify notify = new Notify();

    public Notify getNotify() {
        return notify;
    }

    public void setNotify(Notify notify) {
        this.notify = notify;
    }

    /**
     * 通知出口配置。
     *
     * <p><b>这里没有凭据字段，连「收件人」也尽量不给。</b>
     * 收件人（手机号 / 机器人 token / webhook 地址）走 {@code zmsg.receiverRef}，
     * 由 z-mist 按引用取明文；{@code zmsg.receiverDirect} 只为本地演练留一个口子，
     * 生产不填。
     */
    public static class Notify {

        /** 通知出口总开关。关掉时只有写日志的兜底渠道。 */
        private boolean enabled = false;

        /** z-msg 侧配置。 */
        private ZMsg zmsg = new ZMsg();

        /** 韧性（重试 + 落盘待发 + 补发）配置。 */
        private Resilient resilient = new Resilient();

        /** z-mist（取凭据）配置。 */
        private Mist mist = new Mist();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ZMsg getZmsg() {
            return zmsg;
        }

        public void setZmsg(ZMsg zmsg) {
            this.zmsg = zmsg;
        }

        public Resilient getResilient() {
            return resilient;
        }

        public void setResilient(Resilient resilient) {
            this.resilient = resilient;
        }

        public Mist getMist() {
            return mist;
        }

        public void setMist(Mist mist) {
            this.mist = mist;
        }
    }

    /** z-msg 侧。 */
    public static class ZMsg {

        /** z-msg 服务地址。空 = 不装配 z-msg 渠道。 */
        private String baseUrl = "";

        /** 投递端点。默认批量提交面（走模板，见需求 009 D4）。 */
        private String sendPath = "/api/msg/batch/submit";

        /** 目标通道：SMS / IM_DINGTALK / WEBHOOK … */
        private String channel = "SMS";

        /** 业务类型，对应 z-msg 侧的模板。 */
        private String bizType = "z.observer.alert";

        /** 收件人的 z-mist 引用（group/namespace/secretKey）。生产走这个。 */
        private String receiverRef = "";

        /** 直填收件人。仅本地演练用；生产不填（填了就是明文凭据进配置）。 */
        private String receiverDirect = "";

        /** 连接超时（毫秒）。 */
        private int connectTimeoutMillis = 3000;

        /** 读超时（毫秒）。 */
        private int readTimeoutMillis = 5000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSendPath() {
            return sendPath;
        }

        public void setSendPath(String sendPath) {
            this.sendPath = sendPath;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public String getBizType() {
            return bizType;
        }

        public void setBizType(String bizType) {
            this.bizType = bizType;
        }

        public String getReceiverRef() {
            return receiverRef;
        }

        public void setReceiverRef(String receiverRef) {
            this.receiverRef = receiverRef;
        }

        public String getReceiverDirect() {
            return receiverDirect;
        }

        public void setReceiverDirect(String receiverDirect) {
            this.receiverDirect = receiverDirect;
        }

        public int getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        public int getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        public void setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
        }
    }

    /** 韧性配置。 */
    public static class Resilient {

        /** 总尝试次数（含首次）。 */
        private int maxAttempts = 3;

        /** 退避基数（毫秒），第 n 次重试等 backoff × n。 */
        private long backoffMillis = 500L;

        /** 待发通知的本地暂存目录。 */
        private String spoolDir = "./data/notify-spool";

        /** 补发扫描间隔（毫秒）。 */
        private long replayIntervalMillis = 10_000L;

        /** 补发次数用尽后标记为未送达。 */
        private int maxReplayAttempts = 20;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffMillis() {
            return backoffMillis;
        }

        public void setBackoffMillis(long backoffMillis) {
            this.backoffMillis = backoffMillis;
        }

        public String getSpoolDir() {
            return spoolDir;
        }

        public void setSpoolDir(String spoolDir) {
            this.spoolDir = spoolDir;
        }

        public long getReplayIntervalMillis() {
            return replayIntervalMillis;
        }

        public void setReplayIntervalMillis(long replayIntervalMillis) {
            this.replayIntervalMillis = replayIntervalMillis;
        }

        public int getMaxReplayAttempts() {
            return maxReplayAttempts;
        }

        public void setMaxReplayAttempts(int maxReplayAttempts) {
            this.maxReplayAttempts = maxReplayAttempts;
        }
    }

    /** z-mist（取凭据）配置。 */
    public static class Mist {

        /** z-mist 服务地址。空 = 不装配取密件器。 */
        private String baseUrl = "";

        /** 缺省 group。 */
        private String group = "DEFAULT_GROUP";

        /** 缺省 namespace。 */
        private String namespace = "";

        /** 连接超时（毫秒）。 */
        private int connectTimeoutMillis = 3000;

        /** 读超时（毫秒）。 */
        private int readTimeoutMillis = 5000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public int getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        public int getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        public void setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
        }
    }

    public int getCollectorPort() {
        return collectorPort;
    }

    public void setCollectorPort(int collectorPort) {
        this.collectorPort = collectorPort;
    }

    public String getPrometheusUrl() {
        return prometheusUrl;
    }

    public void setPrometheusUrl(String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    public long getEvaluateIntervalMillis() {
        return evaluateIntervalMillis;
    }

    public void setEvaluateIntervalMillis(long evaluateIntervalMillis) {
        this.evaluateIntervalMillis = evaluateIntervalMillis;
    }

    public int getLogMaxDocs() {
        return logMaxDocs;
    }

    public void setLogMaxDocs(int logMaxDocs) {
        this.logMaxDocs = logMaxDocs;
    }

    public long getLogRetentionMillis() {
        return logRetentionMillis;
    }

    public void setLogRetentionMillis(long logRetentionMillis) {
        this.logRetentionMillis = logRetentionMillis;
    }

    public int getTraceMaxTraces() {
        return traceMaxTraces;
    }

    public void setTraceMaxTraces(int traceMaxTraces) {
        this.traceMaxTraces = traceMaxTraces;
    }

    public long getTraceRetentionMillis() {
        return traceRetentionMillis;
    }

    public void setTraceRetentionMillis(long traceRetentionMillis) {
        this.traceRetentionMillis = traceRetentionMillis;
    }

    public List<RuleSpec> getRules() {
        return rules;
    }

    public void setRules(List<RuleSpec> rules) {
        this.rules = rules;
    }

    public long getScrapeIntervalMillis() {
        return scrapeIntervalMillis;
    }

    public void setScrapeIntervalMillis(long scrapeIntervalMillis) {
        this.scrapeIntervalMillis = scrapeIntervalMillis;
    }

    public int getMetricsPort() {
        return metricsPort;
    }

    public void setMetricsPort(int metricsPort) {
        this.metricsPort = metricsPort;
    }

    public List<String> getNamingServices() {
        return namingServices;
    }

    public void setNamingServices(List<String> namingServices) {
        this.namingServices = namingServices;
    }

    public List<String> getScrapeUrls() {
        return scrapeUrls;
    }

    public void setScrapeUrls(List<String> scrapeUrls) {
        this.scrapeUrls = scrapeUrls;
    }
}
