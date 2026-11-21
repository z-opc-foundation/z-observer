package com.zifang.z.observer.admin.web;

import com.zifang.util.monitor.chart.ChartSeries;
import com.zifang.util.monitor.chart.Sparkline;
import com.zifang.z.meta.api.annotation.BizApi;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizParam;
import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.PrometheusQueryException;
import com.zifang.z.observer.admin.metric.Series;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 指标迷你趋势图端点。
 *
 * <p>指标页的表格此前只有「此刻的值」，趋势要开 Grafana 才看得到 ——
 * 而看一眼「这条时序最近半小时长什么样」是排障时最高频的动作。
 * 这里用 z-util-monitor 的 {@link Sparkline}（服务端 SVG，零前端依赖）
 * 把区间查询直接渲染成一张小图，前端一行 {@code <img>} 就能嵌进表格。
 *
 * <p><b>多条时序只画第一条</b>（按 seriesKey 字典序）：迷你图的语义是
 * 「单条时序的形状」，把多条压进一张小图会互相盖住反而失去信息 ——
 * 要对比请用带 label 过滤的表达式把结果收窄到一条，或看指标页的整表。
 *
 * <p>与 {@code /api/metrics/query-range} 同源的查询失败语义：查询失败抛
 * {@code PrometheusQueryException} 由统一异常处理转 502；
 * 「查得到但只有 0 个点」不报错，回一张透明占位图。
 *
 * <p>区间查询带短 TTL 缓存（含失败负缓存）：表格一页 50 行的趋势图是
 * 50 个同 promql 的并发请求，去重后上游只承受一次。见 {@link #rangeCached}。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricSparkController {

    /** 最多采多少个点：区间再长也按这个数放宽步长，别把 Prometheus 打疼。 */
    private static final int MAX_POINTS = 120;
    private static final int MAX_WIDTH = 640;
    private static final int MAX_HEIGHT = 200;

    /**
     * 区间查询结果短缓存的默认 TTL。
     *
     * <p>指标页一页最多 50 行，每行的趋势图都带着<b>同一个 promql</b> 来请求本端点
     * （seriesKey 只是后端选线参数，发生在区间查询之后）——不缓存的话，一次页面渲染
     * 就是 50 个一模一样的 range 查询并发砸向 Prometheus。15 秒对一张迷你图毫无感知差异，
     * 对上游却是 50 → 1 的差别。
     */
    static final long DEFAULT_CACHE_TTL_MILLIS = 15_000L;

    /**
     * 失败结果的缓存 TTL（比成功短）。查询失败也短缓存一小会儿：
     * Prometheus 挂掉时，50 张图各自挨一遍 5 秒超时是对一个已经死掉的源最残忍的事。
     */
    private static final long FAILURE_CACHE_TTL_MILLIS = 5_000L;

    /** 缓存条目上限（promql 是用户输入，理论上可以无限造 key）：超过就清掉过期项。 */
    private static final int MAX_CACHE_ENTRIES = 512;

    private final MetricQuery metricQuery;
    private final long ttlNanos;
    private final long failureTtlNanos;

    private final ConcurrentMap<String, CachedRange> cache = new ConcurrentHashMap<String, CachedRange>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();

    public MetricSparkController(MetricQuery metricQuery) {
        this(metricQuery, DEFAULT_CACHE_TTL_MILLIS);
    }

    /** 测试口：把 TTL 调成 0 即每次都真查。 */
    MetricSparkController(MetricQuery metricQuery, long cacheTtlMillis) {
        this.metricQuery = metricQuery;
        this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, cacheTtlMillis));
        this.failureTtlNanos = Math.min(this.ttlNanos,
                TimeUnit.MILLISECONDS.toNanos(FAILURE_CACHE_TTL_MILLIS));
    }

    /** 区间查询 → 单条时序 → SVG 迷你图。 */
    @GetMapping(value = "/spark.svg", produces = "image/svg+xml")
    @BizApi(summary = "指标迷你趋势图", description = "把一条时序最近一段时间的形状渲染成 SVG 迷你图，供指标页表格内嵌。", tags = { "z-observer" })
    public ResponseEntity<byte[]> spark(
            @RequestParam("promql") @BizParam(name = "promql", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String promql,
            @RequestParam(value = "minutes", defaultValue = "30")
            @BizParam(name = "minutes", type = BizField.FieldType.INTEGER, description = "", position = BizParam.Position.QUERY) Long minutes,
            @RequestParam(value = "width", defaultValue = "160")
            @BizParam(name = "width", type = BizField.FieldType.INTEGER, description = "", position = BizParam.Position.QUERY) Integer width,
            @RequestParam(value = "height", defaultValue = "32")
            @BizParam(name = "height", type = BizField.FieldType.INTEGER, description = "", position = BizParam.Position.QUERY) Integer height,
            @RequestParam(value = "seriesKey", required = false)
            @BizParam(name = "seriesKey", type = BizField.FieldType.STRING, description = "", position = BizParam.Position.QUERY) String seriesKey) {
        long windowMillis = Math.max(1, minutes.longValue()) * 60_000L;
        int w = Math.min(Math.max(1, width.intValue()), MAX_WIDTH);
        int h = Math.min(Math.max(1, height.intValue()), MAX_HEIGHT);

        List<Series> points = rangeCached(promql, windowMillis);

        String svg = Sparkline.render(pickSeries(points, seriesKey, promql), w, h);
        if (svg.isEmpty()) {
            // 查得到表达式但区间里一个点都没有：不是失败，回一张透明占位图
            svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + w
                    + "\" height=\"" + h + "\"></svg>";
        }
        return ResponseEntity.status(HttpStatus.OK)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                .body(svg.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 带短 TTL 与并发去重的区间查询。
     *
     * <p>缓存键是 {@code promql @ 窗口长度}（step 由窗口唯一决定），<b>不含</b>
     * {@code seriesKey} 与查询时刻 —— 前者只是选线参数，后者让窗口边缘最多漂移
     * 一个 TTL，对一张迷你图不可见。同一键并发请求只放一个进去真查，
     * 其余等锁后直接取结果：50 行同时渲染也只打一次上游。
     *
     * <p>失败同样被短缓存（TTL 更短）：同键的后续请求直接复用这次失败，
     * 而不是对一个已经不响应的源反复制造超时。
     */
    private List<Series> rangeCached(String promql, long windowMillis) {
        long step = Math.max(60_000L, windowMillis / MAX_POINTS);
        String key = promql + "@" + windowMillis;
        CachedRange hit = cache.get(key);
        if (hit != null && fresh(hit, System.nanoTime())) {
            return hit.pointsOrThrow();
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            hit = cache.get(key);
            if (hit != null && fresh(hit, System.nanoTime())) {
                return hit.pointsOrThrow();
            }
            long to = System.currentTimeMillis();
            try {
                List<Series> points = metricQuery.range(promql, to - windowMillis, to, step);
                cache.put(key, CachedRange.ok(points, System.nanoTime()));
                if (cache.size() > MAX_CACHE_ENTRIES) {
                    prune();
                }
                return points;
            } catch (PrometheusQueryException e) {
                cache.put(key, CachedRange.failure(e, System.nanoTime()));
                throw e;
            }
        }
    }

    private boolean fresh(CachedRange hit, long nowNanos) {
        long ttl = hit.error != null ? failureTtlNanos : ttlNanos;
        return ttl > 0 && nowNanos - hit.loadedAtNanos < ttl;
    }

    /** promql 是自由输入，key 理论上无限多：超限时把已过期的条目清掉即可，不做 LRU。 */
    private void prune() {
        long now = System.nanoTime();
        for (Map.Entry<String, CachedRange> e : cache.entrySet()) {
            if (!fresh(e.getValue(), now)) {
                cache.remove(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * 按 seriesKey 选要画的那条时序；没指定或缺这条时序就画字典序第一条。
     *
     * <p>多条只画一条的语义见类注释 —— seriesKey 参数是给「整表里逐行各画各的」
     * 用的：前端把行自己的 seriesKey 回传上来。
     */
    private static ChartSeries pickSeries(List<Series> points, String seriesKey, String promql) {
        Map<String, ChartSeries.Builder> builders = new LinkedHashMap<String, ChartSeries.Builder>();
        for (Series point : points) {
            builders.computeIfAbsent(point.seriesKey(),
                    k -> ChartSeries.builder(k)).point(point.timestampMillis(), point.value());
        }
        if (builders.isEmpty()) {
            return ChartSeries.builder(promql).build();
        }
        String chosen = seriesKey != null && builders.containsKey(seriesKey)
                ? seriesKey : builders.keySet().iterator().next();
        return builders.get(chosen).build();
    }

    /** 一份区间查询结果：成功时装点集，失败时装异常（异常也缓存，理由见 rangeCached）。 */
    private static final class CachedRange {
        private final List<Series> points;
        private final PrometheusQueryException error;
        private final long loadedAtNanos;

        private CachedRange(List<Series> points, PrometheusQueryException error, long loadedAtNanos) {
            this.points = points;
            this.error = error;
            this.loadedAtNanos = loadedAtNanos;
        }

        static CachedRange ok(List<Series> points, long loadedAtNanos) {
            return new CachedRange(points, null, loadedAtNanos);
        }

        static CachedRange failure(PrometheusQueryException e, long loadedAtNanos) {
            return new CachedRange(null, e, loadedAtNanos);
        }

        List<Series> pointsOrThrow() {
            if (error != null) {
                throw error;
            }
            return points;
        }
    }
}
