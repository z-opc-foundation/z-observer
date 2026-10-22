package com.zifang.z.observer.starter.transport;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * z.observer.transport.* 配置。
 *
 * <p>{@code enabled=false} 时上报通道整体不装配 —— 这是给不需要观测通道的进程
 * （比如短命 CLI、批处理 worker）留的开关，而不是开关某一个 Appender：
 * 半接的观测比不接更难排查。
 */
@ConfigurationProperties(prefix = "z.observer.transport")
public class ObserverTransportProperties {

    private boolean enabled = true;
    private String host;
    private int port = 4318;

    /** 本实例标识，随每批数据上报。缺省由 Spring 的 applicationName + 端口拼。 */
    private String instanceId;

    private int queueCapacity = 8192;
    private int batchSize = 512;
    private long flushIntervalMillis = 1000;

    /**
     * 采集口共享密钥（客户端鉴权用）。空 = 不发鉴权握手帧。
     *
     * <p><b>本仓不填它</b>：密钥属于「谁连收端」那一方，由消费方的配置提供
     * （推荐 {@code Z_OBSERVER_TRANSPORT_AUTH_TOKEN} 环境变量 / Secret）。
     * 收端开了鉴权而客户端没配 ⇒ 收端会断掉这条连接（这是对的：
     * 没配密钥的客户端不该被收端接受）。
     *
     * <p>密钥**不进配置文件、不进仓**：配置类里只有「怎么取」的口子，
     * 值本身由运行环境注入。
     */
    private String authToken = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getFlushIntervalMillis() {
        return flushIntervalMillis;
    }

    public void setFlushIntervalMillis(long flushIntervalMillis) {
        this.flushIntervalMillis = flushIntervalMillis;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }
}