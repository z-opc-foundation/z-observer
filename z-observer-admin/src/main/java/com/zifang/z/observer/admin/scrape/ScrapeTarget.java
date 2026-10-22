package com.zifang.z.observer.admin.scrape;

/** 一个被采集的 target。 */
public final class ScrapeTarget {

    /**
     * 被观测方的默认指标路径。
     *
     * <p>取 {@code /actuator/prometheus}，与 {@code z-observer-starter} 暴露的路径
     * （{@code z.observer.metrics.endpoint} 的默认值）以及 Spring Boot actuator 的
     * 约定路径一致。
     *
     * <p><b>为什么不是 {@code /metrics}：</b> 那条路径是<b>收端自己</b>暴露自指标的路径
     * （{@code SelfMetricsController}），SDK 侧并不暴露它 ——
     * 用它当默认值，等于「按端口拼 target」这件事永远拼不出一个能采的地址
     * （采过去全是 404，而且失败原因看起来像「服务挂了」）。
     */
    public static final String DEFAULT_METRICS_PATH = "/actuator/prometheus";

    private final String id;
    private final String url;

    public ScrapeTarget(String id, String url) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("target id 必填");
        }
        if (url == null || !url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("采集地址必须是 http/https: " + url);
        }
        this.id = id;
        this.url = url;
    }

    public String id() {
        return id;
    }

    public String url() {
        return url;
    }

    /** 这个 target 该带哪些标签（app/instance 等），也决定告警里怎么称呼它。 */
    public String instanceLabel() {
        return id;
    }

    @Override
    public String toString() {
        return id + " -> " + url;
    }
}
