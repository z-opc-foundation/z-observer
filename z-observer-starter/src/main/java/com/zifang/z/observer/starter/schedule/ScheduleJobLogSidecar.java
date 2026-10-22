package com.zifang.z.observer.starter.schedule;

import com.zifang.z.observer.core.metric.MetricRegistry;
import com.zifang.z.observer.core.metric.PrometheusTextWriter;
import com.zifang.z.observer.core.model.Labels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * z-schedule 的 push 边车（独立进程形态）。
 *
 * <p><b>它收口的是 pull 覆盖不到的那一类进程</b>：短生命周期的批任务 ——
 * 跑完就退出，Prometheus 还没来得及抓它，它已经没了。这类进程的指标只能「推」。
 *
 * <p><b>z-schedule 侧零源码改动</b>：边车只做只读轮询（{@code GET /api/schedule/joblog/list}），
 * 复用 z-schedule <b>已经有的</b>任务执行日志；不加依赖、不改代码、不注册钩子。
 * 改 z-schedule 的代价是每个域的调度仓都要跟着观测 SDK 走一遍，
 * 而「观测」这件事本来不该要求被观测方改代码。
 *
 * <p><b>边车自己的指标面</b>：边车是常驻进程（不是被观测的短命任务），
 * 所以它把自己的指标按 Prometheus 文本格式暴露在自己的端口上，
 * 由 Prometheus 抓 —— 与「采集端拉被观测方」是同一个格式契约，只是方向反过来。
 *
 * <p><b>配置</b>（全部来自环境变量 / 系统属性，<b>值不进仓</b>）：
 * <pre>
 * Z_OBSERVER_SIDECAR_SCHEDULE_URL   必填，z-schedule 管理面地址（含路径）
 * Z_OBSERVER_SIDECAR_INTERVAL_MILLIS  轮询周期，默认 15000
 * Z_OBSERVER_SIDECAR_PORT          边车自己的指标端口，默认 19100
 * Z_OBSERVER_SIDECAR_AUTH_HEADER   可选，透传给 z-schedule 的请求头（如 Cookie / Authorization）
 * Z_OBSERVER_SIDECAR_APP_NAME      指标里的 app 标签，默认 z-schedule
 * </pre>
 *
 * <p>启动：{@code java -cp ... com.zifang.z.observer.starter.schedule.ScheduleJobLogSidecar}。
 */
public final class ScheduleJobLogSidecar {

    private static final Logger LOG = LogManager.getLogger(ScheduleJobLogSidecar.class);
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final String scheduleUrl;
    private final long intervalMillis;
    private final int metricsPort;
    private final String authHeader;
    private final String appName;

    private final MetricRegistry registry = new MetricRegistry();
    private final Map<String, com.zifang.z.observer.core.metric.Counter> successCounters =
            new ConcurrentHashMap<String, com.zifang.z.observer.core.metric.Counter>();
    private final Map<String, com.zifang.z.observer.core.metric.Counter> failureCounters =
            new ConcurrentHashMap<String, com.zifang.z.observer.core.metric.Counter>();
    private final Map<String, com.zifang.z.observer.core.metric.Gauge> lastRunGauges =
            new ConcurrentHashMap<String, com.zifang.z.observer.core.metric.Gauge>();
    private final AtomicLong lastSeenLogId = new AtomicLong(0L);
    private final AtomicLong pollFailures = new AtomicLong();
    private final AtomicLong pollSuccess = new AtomicLong();

    public ScheduleJobLogSidecar(String scheduleUrl, long intervalMillis, int metricsPort,
                                  String authHeader, String appName) {
        if (scheduleUrl == null || scheduleUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("必须给出 z-schedule 管理面地址");
        }
        this.scheduleUrl = scheduleUrl.trim();
        this.intervalMillis = intervalMillis <= 0 ? 15_000L : intervalMillis;
        this.metricsPort = metricsPort;
        this.authHeader = authHeader;
        this.appName = appName == null || appName.isEmpty() ? "z-schedule" : appName;
    }

    public static void main(String[] args) throws Exception {
        String url = env("Z_OBSERVER_SIDECAR_SCHEDULE_URL", arg(args, 0));
        long interval = Long.parseLong(env("Z_OBSERVER_SIDECAR_INTERVAL_MILLIS", "15000"));
        int port = Integer.parseInt(env("Z_OBSERVER_SIDECAR_PORT", "19100"));
        String header = env("Z_OBSERVER_SIDECAR_AUTH_HEADER", null);
        String app = env("Z_OBSERVER_SIDECAR_APP_NAME", "z-schedule");

        final ScheduleJobLogSidecar sidecar = new ScheduleJobLogSidecar(
                url, interval, port, header, app);
        sidecar.startMetricsEndpoint();
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "z-observer-schedule-sidecar");
            t.setDaemon(true);
            return t;
        });
        pool.scheduleWithFixedDelay(sidecar::pollOnce, 0L, sidecar.intervalMillis, TimeUnit.MILLISECONDS);
        LOG.info("z-schedule 边车已启动：轮询 {} 每 {} ms，指标端口 {}",
                sidecar.scheduleUrl, Long.valueOf(sidecar.intervalMillis), Integer.valueOf(port));
        // 常驻：main 线程不退出
        Thread.currentThread().join();
    }

    /** 跑一轮轮询。拉不到就记一次失败，不抛 —— 边车挂了监控就瞎了。 */
    public void pollOnce() {
        try {
            String body = get(scheduleUrl, authHeader);
            List<ScheduleJobEventParser.JobEvent> events = ScheduleJobEventParser.parse(body);
            int counted = 0;
            for (ScheduleJobEventParser.JobEvent e : events) {
                if (e.id() <= 0 || e.id() <= lastSeenLogId.get()) {
                    // 幂等：同一行日志只算一次（每轮都会拉到同一批历史行）
                    continue;
                }
                record(e);
                lastSeenLogId.accumulateAndGet(e.id(), Math::max);
                counted++;
            }
            pollSuccess.incrementAndGet();
            if (counted > 0) {
                LOG.info("边车本轮新增 {} 条任务执行事件（已见到日志 id={}）",
                        Integer.valueOf(counted), Long.valueOf(lastSeenLogId.get()));
            }
        } catch (Throwable e) {
            // ⚠ 这里接的是 **Throwable** 而不是 Exception：
            //   少一个 jar（NoClassDefFoundError）属于 Error，接不住 ⇒ 定时任务直接死掉，
            //   而「边车还活着却早就没在采」是**静默死亡** —— 比报错更难查。
            //   边车这种「不许自己死」的角色，宁可把 Error 也记成一次轮询失败。
            pollFailures.incrementAndGet();
            LOG.warn("边车轮询失败（不抛：边车挂了监控就瞎了）: {}", e.toString());
        }
    }

    private void record(ScheduleJobEventParser.JobEvent e) {
        // ⚠ 标签名不能叫 job：MetricRegistry 把 job/instance/le/quantile 留给「抓取端注入」，
        //   业务侧占用会被直接拒（标签名白名单在注册期就挡）。
        //   ⇒ 任务名用 task（job_handler 的语义），与抓取端的 job 标签不打架。
        Labels labels = Labels.builder()
                .put("app", appName)
                .put("task", e.job())
                .build();
        if (e.isSuccess()) {
            counter(successCounters, "z_observer_schedule_job_execution_total",
                    "任务执行次数（按结果分）", labels, "success").inc();
        } else {
            counter(failureCounters, "z_observer_schedule_job_execution_total",
                    "任务执行次数（按结果分）", labels, "failure").inc();
        }
        if (e.timestampMillis() > 0) {
            gauge(lastRunGauges, "z_observer_schedule_job_last_execution_timestamp_seconds",
                    "任务最近一次执行时间（秒）", Labels.builder()
                            .put("app", appName)
                            .put("task", e.job())
                            .build()).set(e.timestampMillis() / 1000.0d);
        }
    }

    private com.zifang.z.observer.core.metric.Counter counter(
            Map<String, com.zifang.z.observer.core.metric.Counter> cache,
            String name, String help, Labels labels, String status) {
        String key = name + "|" + labels.seriesKey() + "|" + status;
        com.zifang.z.observer.core.metric.Counter c = cache.get(key);
        if (c == null) {
            Labels withStatus = Labels.builder()
                    .put("app", labels.asMap().get("app"))
                    .put("task", labels.asMap().get("task"))
                    .put("status", status)
                    .build();
            c = registry.counter(name, help, withStatus);
            cache.put(key, c);
        }
        return c;
    }

    private com.zifang.z.observer.core.metric.Gauge gauge(
            Map<String, com.zifang.z.observer.core.metric.Gauge> cache,
            String name, String help, Labels labels) {
        String key = name + "|" + labels.seriesKey();
        com.zifang.z.observer.core.metric.Gauge g = cache.get(key);
        if (g == null) {
            g = registry.gauge(name, help, labels);
            cache.put(key, g);
        }
        return g;
    }

    /** 边车自己的指标文本（Prometheus text format 0.0.4）。 */
    public String exposition() {
        registry.counter("z_observer_schedule_sidecar_poll_failures_total",
                "边车轮询失败累计次数", Labels.empty()).inc(pollFailures.get());
        registry.counter("z_observer_schedule_sidecar_poll_success_total",
                "边车轮询成功累计次数", Labels.empty()).inc(pollSuccess.get());
        registry.gauge("z_observer_schedule_sidecar_last_seen_log_id",
                "边车已处理到的最大调度日志 id", Labels.empty()).set(lastSeenLogId.get());
        return new PrometheusTextWriter(registry).render();
    }

    /** 起一个只读 HTTP 端口暴露自己的指标。 */
    public void startMetricsEndpoint() {
        try {
            final com.sun.net.httpserver.HttpServer server =
                    com.sun.net.httpserver.HttpServer.create(
                            new InetSocketAddress(metricsPort), 0);
            server.createContext("/metrics", new com.sun.net.httpserver.HttpHandler() {
                @Override
                public void handle(com.sun.net.httpserver.HttpExchange x) throws java.io.IOException {
                    byte[] body = exposition().getBytes(UTF8);
                    x.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                    x.sendResponseHeaders(200, body.length);
                    x.getResponseBody().write(body);
                    x.close();
                }
            });
            server.start();
            LOG.info("边车指标端点已起: http://0.0.0.0:{}/metrics", Integer.valueOf(metricsPort));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("边车指标端口起不来: " + metricsPort, e);
        }
    }

    /** 只读 GET。 */
    static String get(String url, String authHeader) throws java.io.IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");
            if (authHeader != null && !authHeader.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", authHeader.trim());
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new java.io.IOException("z-schedule 返回 HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), UTF8);
        } finally {
            conn.disconnect();
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            v = System.getProperty(key);
        }
        if (v == null || v.trim().isEmpty()) {
            return fallback;
        }
        return v;
    }

    private static String arg(String[] args, int index) {
        if (args == null || args.length <= index) {
            return null;
        }
        return args[index];
    }
}
