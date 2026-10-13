package com.zifang.z.observer.admin.web;

import com.zifang.z.observer.admin.collect.FrameDispatcher;
import com.zifang.z.observer.admin.collect.HeartbeatTracker;
import com.zifang.z.observer.admin.rule.RuleEngine;
import com.zifang.z.observer.admin.scrape.Scraper;
import com.zifang.z.observer.admin.store.LogStore;
import com.zifang.z.observer.admin.store.TraceStore;
import com.zifang.z.observer.core.metric.Counter;
import com.zifang.z.observer.core.metric.Gauge;
import com.zifang.z.observer.core.model.Labels;
import com.zifang.z.observer.core.metric.MetricRegistry;
import com.zifang.z.observer.core.metric.PrometheusTextWriter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 收端自己的 Prometheus 文本暴露端点。
 *
 * <p>用 core 里那套自研 writer 而不是 Micrometer：与被观测方完全同一套格式与口径，
 * 收端不会被自己这套系统判为「格式不兼容」。
 *
 * <p>淘汰数、坏帧数、评估错误数都在这里 —— 「查不到数据」这件事本身必须可观测，
 * 否则第一次遇到「昨天的日志查不到了」只能靠猜。
 */
@RestController
public class SelfMetricsController {

    private final LogStore logStore;
    private final TraceStore traceStore;
    private final FrameDispatcher dispatcher;
    private final HeartbeatTracker heartbeats;
    private final RuleEngine engine;
    private final Scraper scraper;

    public SelfMetricsController(LogStore logStore, TraceStore traceStore,
                                 FrameDispatcher dispatcher, HeartbeatTracker heartbeats,
                                 RuleEngine engine, Scraper scraper) {
        this.logStore = logStore;
        this.traceStore = traceStore;
        this.dispatcher = dispatcher;
        this.heartbeats = heartbeats;
        this.engine = engine;
        this.scraper = scraper;
    }

    @GetMapping(value = "/metrics", produces = "text/plain;version=0.0.4;charset=utf-8")
    public String expose() throws IOException {
        MetricRegistry registry = new MetricRegistry();
        Labels none = Labels.empty();

        Counter frames = registry.counter("z_observer_frames_total", "收端收到的帧数", none);
        frames.inc(dispatcher.receivedCount());
        Counter rejected = registry.counter("z_observer_frames_rejected_total", "解码或结构对不上的帧数", none);
        rejected.inc(dispatcher.rejectedCount());
        Counter logBatches = registry.counter("z_observer_log_batches_total", "日志批次数", none);
        logBatches.inc(dispatcher.logBatchCount());
        Counter spanBatches = registry.counter("z_observer_span_batches_total", "span 批次数", none);
        spanBatches.inc(dispatcher.spanBatchCount());

        // 淘汰数是 gauge 不是 counter：进程重启会归零，它是「当前存量」而不是累计事件
        Gauge liveLogs = registry.gauge("z_observer_live_logs", "当前留存的日志条数", none);
        liveLogs.set(logStore.size());
        Gauge evictedLogs = registry.gauge("z_observer_evicted_logs_total", "被淘汰的日志条数", none);
        evictedLogs.set(logStore.evictedCount());
        Gauge liveTraces = registry.gauge("z_observer_live_traces", "当前留存的链路条数", none);
        liveTraces.set(traceStore.traceCount());
        Gauge evictedTraces = registry.gauge("z_observer_evicted_traces_total", "被淘汰的链路条数", none);
        evictedTraces.set(traceStore.evictedCount());
        Gauge truncatedSpans = registry.gauge("z_observer_truncated_spans_total", "因超单链路上限被截断的 span 数", none);
        truncatedSpans.set(traceStore.truncatedCount());
        Gauge trackedInstances = registry.gauge("z_observer_tracked_instances", "已登记心跳的实例数", none);
        trackedInstances.set(heartbeats.trackedCount());
        Gauge evalErrors = registry.gauge("z_observer_rule_eval_errors_total", "规则评估轮次里查询失败的次数", none);
        evalErrors.set(engine.evaluateErrorCount());
        Gauge scrapeFailures = registry.gauge("z_observer_scrape_failures_total", "pull 采集失败次数", none);
        scrapeFailures.set(scraper.failureCount());
        Gauge oversizeResponses = registry.gauge("z_observer_scrape_oversize_total", "响应体超限被整份丢弃的次数", none);
        oversizeResponses.set(scraper.oversizeCount());

        return new PrometheusTextWriter(registry).render();
    }
}
