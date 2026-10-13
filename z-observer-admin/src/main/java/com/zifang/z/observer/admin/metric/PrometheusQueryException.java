package com.zifang.z.observer.admin.metric;

/**
 * 查询请求本身失败（网络、HTTP 错误码、响应不合法、PromQL 语法错）。
 *
 * <p>与「查询成功但没有匹配的时序」严格区分：前者是采集面坏了，
 * 后者是业务事实。规则引擎必须在两者上表现完全不同 —— 把前者当成后者，
 * Prometheus 自己抖一下就会触发一批 absent 告警。
 */
public class PrometheusQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PrometheusQueryException(String message) {
        super(message);
    }

    public PrometheusQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
