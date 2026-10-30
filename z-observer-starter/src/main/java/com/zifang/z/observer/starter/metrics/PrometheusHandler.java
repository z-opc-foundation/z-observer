package com.zifang.z.observer.starter.metrics;

import com.zifang.z.observer.core.metric.MetricRegistry;
import com.zifang.z.observer.core.metric.PrometheusTextWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 暴露 Prometheus 文本格式的 HTTP 处理器。挂在 {@code /actuator/prometheus}。
 *
 * <p><b>有响应体上限，超限是 503 而不是截断。</b> Prometheus 默认 50MB 拒收整个响应体，
 * 超了直接丢弃 —— 而现实中踩坑最多的是 Java 应用把 200MB 打到
 * {@code /actuator/prometheus}、收端一整块丢掉、监控面板集体变空，且日志里什么都没有。
 * 这里提前拦一道，并且把被拦的次数暴露成指标，让"抓不到"这件事自己会说话。
 *
 * <p>真要解决根因只能去降基数（限制维度），而不是放宽上限。
 */
public final class PrometheusHandler {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 与 Prometheus 的默认上限对齐，提前在服务端拒掉而不是让收端静默丢弃。 */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 50L * 1024 * 1024;

    private final MetricRegistry registry;
    private final long maxResponseBytes;
    private final AtomicLong oversizedScrapes = new AtomicLong();
    private final PrometheusTextWriter writer;

    public PrometheusHandler(MetricRegistry registry) {
        this(registry, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public PrometheusHandler(MetricRegistry registry, long maxResponseBytes) {
        this.registry = registry;
        this.maxResponseBytes = maxResponseBytes;
        this.writer = new PrometheusTextWriter(registry);
    }

    public long oversizedScrapeCount() {
        return oversizedScrapes.get();
    }

    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] body;
        try {
            body = writer.render().getBytes(UTF8);
        } catch (RuntimeException e) {
            // 采集期出错不能返回 200 空体：那等于告诉收端"这个目标还活着且指标为零"，
            // 比直接报错危险得多 —— 面板会安安静静地显示一片零。
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/plain; charset=utf-8");
            response.getWriter().write("metrics collection failed: " + e);
            return;
        }

        if (body.length > maxResponseBytes) {
            oversizedScrapes.incrementAndGet();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("text/plain; charset=utf-8");
            response.getWriter().write("metrics response too large: " + body.length
                    + " bytes exceeds " + maxResponseBytes + ". "
                    + "降基数（少埋高基数标签）才能解决，放宽上限只会把压力推到收端");
            return;
        }

        response.setStatus(HttpServletResponse.SC_OK);
        // Prometheus 的文本格式没有显式字符集声明，但内容里可以有 UTF-8 标签值，
        // 缺 charset 会让收端按 latin-1 解，中文标签值直接乱码。
        response.setContentType("text/plain; version=0.0.4; charset=utf-8");
        response.setContentLength(body.length);
        OutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}