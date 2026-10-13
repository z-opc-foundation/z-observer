package com.zifang.z.observer.starter.metrics;

import com.zifang.z.observer.core.metric.MetricRegistry;
import com.zifang.z.observer.core.model.Labels;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 指标自动装配。消费方加这一个依赖就完成埋点接入，不需要写任何配置类。
 *
 * <p>全部用 {@code @ConditionalOnMissingBean}：应用方想换掉注册表、想自己暴露端点
 * （比如把指标混进已有的 /metrics），都能直接覆盖，不用 fork 本 SDK。
 */
@Configuration
@EnableConfigurationProperties(ObserverMetricsProperties.class)
public class ObserverMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetricRegistry metricRegistry(ObserverMetricsProperties properties) {
        Labels.Builder common = Labels.builder();
        for (Map.Entry<String, String> e
                : properties.getMetrics().getCommonTags().entrySet()) {
            common.put(e.getKey(), e.getValue());
        }
        return new MetricRegistry(common.build(), properties.getMetrics().getMaxSeriesPerMetric());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public PrometheusHandler prometheusHandler(MetricRegistry registry,
                                               ObserverMetricsProperties properties) {
        return new PrometheusHandler(registry, properties.getMetrics().getMaxResponseBytes());
    }

    /**
     * 把暴露端点挂到配置路径上。
     *
     * <p>不用 Actuator 的 {@code @Endpoint}：那要求 classpath 上有 actuator，
     * 而观测 SDK 不该强迫消费方引入 actuator —— 它是可选能力，不是运行前提。
     * 注册成 servlet 而不是 bean，是为了让路径与 actuator 一致却互不干扰 ——
     * 采方按 {@code /actuator/prometheus} 配置，不用因为本 SDK 而打开 actuator。
     */
    @Bean
    @ConditionalOnMissingBean(name = "zObserverPrometheusEndpoint")
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public ServletRegistrationBean<PrometheusServlet> zObserverPrometheusEndpoint(
            PrometheusHandler handler, ObserverMetricsProperties properties) {
        if (!properties.getMetrics().isEnabled()) {
            return null;
        }
        return new ServletRegistrationBean<PrometheusServlet>(
                new PrometheusServlet(handler), properties.getMetrics().getEndpoint());
    }
}