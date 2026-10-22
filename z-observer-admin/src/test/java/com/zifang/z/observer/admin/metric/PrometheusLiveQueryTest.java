package com.zifang.z.observer.admin.metric;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 打真 Prometheus 的活体验证（卡 TASK-20261012-006 验收第 1 项）。
 *
 * <p><b>默认跳过</b>：只有显式给出 {@code Z_OBSERVER_LIVE_PROMETHEUS_URL} 才跑，
 * CI 上没有 Prometheus 时不能因为环境缺失而红。
 *
 * <pre>
 * Z_OBSERVER_LIVE_PROMETHEUS_URL=http://127.0.0.1:9090 \
 *   mvn -o -pl z-observer-admin test -Dtest=PrometheusLiveQueryTest
 * </pre>
 *
 * <p>跑的是<b>真适配器</b>（{@link PrometheusHttpClient}），不是 mock ——
 * 验收口径是「3 类查询对现网 Prometheus 跑通」。
 */
class PrometheusLiveQueryTest {

    private static final String URL = System.getenv("Z_OBSERVER_LIVE_PROMETHEUS_URL");

    private PrometheusHttpClient client;

    @BeforeEach
    void requireLivePrometheus() {
        assumeTrue(URL != null && !URL.trim().isEmpty(),
                "未设置 Z_OBSERVER_LIVE_PROMETHEUS_URL，跳过活体验证");
        client = new PrometheusHttpClient(URL, 5000);
    }

    @Test
    void 瞬时值查询跑通() {
        List<Series> series = client.instant("up", 0);

        System.out.println("[LIVE] instant(up) -> " + series);
        assertThat(series).isNotEmpty();
        assertThat(series.get(0).value()).isFinite();
    }

    @Test
    void 区间向量查询跑通() {
        long end = System.currentTimeMillis();
        long start = end - 300_000L;

        List<Series> series = client.range("up", start, end, 30_000L);

        System.out.println("[LIVE] range(up, 300s, step=30s) -> " + series.size() + " 点, 样例 " + series);
        assertThat(series).isNotEmpty();
        // 区间向量至少要有多个采样点，否则谈不上「向量」。
        assertThat(series.size()).isGreaterThan(1);
    }

    @Test
    void 标量查询跑通() {
        double value = client.scalar("scalar(count(up))", 0);

        System.out.println("[LIVE] scalar(count(up)) -> " + value);
        assertThat(value).isFinite();
        assertThat(value).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void 真实业务指标查询跑通() {
        // z-env 的 Prometheus 自己抓自己 ⇒ prometheus_http_requests_total 是真实请求流量。
        List<Series> series = client.instant("rate(prometheus_http_requests_total[1m])", 0);

        System.out.println("[LIVE] instant(rate(prometheus_http_requests_total[1m])) -> " + series);
        assertThat(series).isNotEmpty();
    }

    @Test
    void 查不到的时序返回空列表而不是报错() {
        // 「没有这条时序」是业务事实，与「问不动 Prometheus」必须分开。
        List<Series> series = client.instant("z_definitely_no_such_metric_xyz", 0);

        System.out.println("[LIVE] instant(不存在的指标) -> " + series);
        assertThat(series).isEmpty();
        assertThat(client.failureCount()).isZero();
    }
}
