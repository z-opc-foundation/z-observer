package com.zifang.z.observer.admin;

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

    /** 采集间隔。 */
    private long scrapeIntervalMillis = 15_000L;

    /** 被观测方暴露 /metrics 的端口。 */
    private int metricsPort = 9100;

    /** 订阅 z-config naming 的服务名清单。 */
    private List<String> namingServices = new ArrayList<String>();

    /** 静态采集地址（z-config 未就绪时的过渡配置）。 */
    private List<String> scrapeUrls = new ArrayList<String>();

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
