package com.zifang.z.observer.starter.metrics;

import com.zifang.z.observer.core.metric.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配测试。要证明的是「加一个依赖就生效」这件事，而不是逐个方法测 bean ——
 * 装配错在真实项目里表现为端点 404，比单测失败难查得多。
 */
class ObserverMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObserverMetricsAutoConfiguration.class));

    @Test
    void 无任何配置也能装配出注册表() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MetricRegistry.class);
            assertThat(ctx.getBean(MetricRegistry.class).commonLabels().isEmpty()).isTrue();
        });
    }

    @Test
    void 配置的公共标签进入注册表() {
        runner.withPropertyValues(
                "z.observer.metrics.common-tags.app=z-ops",
                "z.observer.metrics.common-tags.env=prod",
                "z.observer.metrics.common-tags.instance=host-1").run(ctx -> {
                    MetricRegistry r = ctx.getBean(MetricRegistry.class);
                    assertThat(r.commonLabels().asMap())
                            .containsEntry("app", "z-ops")
                            .containsEntry("env", "prod")
                            .containsEntry("instance", "host-1");
                });
    }

    @Test
    void 基数上限可配() {
        runner.withPropertyValues("z.observer.metrics.max-series-per-metric=7")
                .run(ctx -> assertThat(
                        ctx.getBean(MetricRegistry.class).maxSeriesPerMetric()).isEqualTo(7));
    }

    @Test
    void 关闭时不注册暴露端点() {
        runner.withPropertyValues("z.observer.metrics.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("zObserverPrometheusEndpoint"));
    }

    @Test
    void 应用方自定义的注册表优先生效() {
        runner.withBean(MetricRegistry.class, () -> new MetricRegistry())
                .run(ctx -> {
                    MetricRegistry mine = ctx.getBean(MetricRegistry.class);
                    assertThat(mine.maxSeriesPerMetric())
                            .isEqualTo(MetricRegistry.DEFAULT_MAX_SERIES_PER_METRIC);
                });
    }
}