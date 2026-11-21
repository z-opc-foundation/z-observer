package com.zifang.z.observer.admin.web;

import com.zifang.util.monitor.chart.ChartSeries;
import com.zifang.util.monitor.chart.Sparkline;
import com.zifang.z.meta.api.annotation.BizApi;
import com.zifang.z.meta.api.annotation.BizField;
import com.zifang.z.meta.api.annotation.BizParam;
import com.zifang.z.observer.admin.metric.MetricQuery;
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
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricSparkController {

    /** 最多采多少个点：区间再长也按这个数放宽步长，别把 Prometheus 打疼。 */
    private static final int MAX_POINTS = 120;
    private static final int MAX_WIDTH = 640;
    private static final int MAX_HEIGHT = 200;

    private final MetricQuery metricQuery;

    public MetricSparkController(MetricQuery metricQuery) {
        this.metricQuery = metricQuery;
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

        long to = System.currentTimeMillis();
        long from = to - windowMillis;
        long step = Math.max(60_000L, windowMillis / MAX_POINTS);
        List<Series> points = metricQuery.range(promql, from, to, step);

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
}
