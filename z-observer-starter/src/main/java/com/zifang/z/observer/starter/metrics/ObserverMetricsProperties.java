package com.zifang.z.observer.starter.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * z.observer.* 配置项。
 *
 * <p>公共标签在配置里给，一处配全组织统一；埋点代码里不许再出现 app/instance 字样。
 */
@ConfigurationProperties(prefix = "z.observer")
public class ObserverMetricsProperties {

    private final Metrics metrics = new Metrics();

    public Metrics getMetrics() {
        return metrics;
    }

    public static class Metrics {

        /** 暴露路径，与 Spring Boot Actuator 的自定义端点惯例一致。 */
        private String endpoint = "/actuator/prometheus";

        /** 是否注册暴露端点。关掉后可自行提供 /metrics。 */
        private boolean enabled = true;

        /** 单条指标名下的时序数上限。 */
        private int maxSeriesPerMetric = 10000;

        /** 暴露响应体上限，对齐 Prometheus 的 50MB 默认拒收线。 */
        private long maxResponseBytes = PrometheusHandler.DEFAULT_MAX_RESPONSE_BYTES;

        /** 公共标签（app / env / instance / version ...），注入每条时序。 */
        private Map<String, String> commonTags = new LinkedHashMap<String, String>();

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxSeriesPerMetric() {
            return maxSeriesPerMetric;
        }

        public void setMaxSeriesPerMetric(int maxSeriesPerMetric) {
            this.maxSeriesPerMetric = maxSeriesPerMetric;
        }

        public long getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(long maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        public Map<String, String> getCommonTags() {
            return commonTags;
        }

        public void setCommonTags(Map<String, String> commonTags) {
            this.commonTags = commonTags;
        }
    }
}