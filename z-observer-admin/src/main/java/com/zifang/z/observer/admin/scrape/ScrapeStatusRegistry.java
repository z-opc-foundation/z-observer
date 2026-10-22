package com.zifang.z.observer.admin.scrape;

import com.zifang.z.observer.core.metric.MetricRegistry;
import com.zifang.z.observer.core.metric.PrometheusTextWriter;
import com.zifang.z.observer.core.model.Labels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集状态登记处：<b>absent 数据源</b>。
 *
 * <p>「抓不到」这件事必须落成一个<b>机器能读到</b>的结构，否则它只存在于日志里，
 * 而 absent 告警恰恰是靠「读到这个 target 现在缺席」来触发的
 * （对应 M1「进程挂了 60 秒内到人」的判据）。
 *
 * <p>登记处对外给三样东西：
 * <ol>
 *   <li><b>状态</b>：每个 target 现在是 up 还是 absent（{@link ScrapeStatus#isAbsent()}）；</li>
 *   <li><b>事件</b>：最近若干条采集事件（{@link #events()}），能回放「什么时候开始抓不到」；</li>
 *   <li><b>exposition</b>：{@link #toExposition()} 把状态渲染成 Prometheus 文本
 *       （{@code z_observer_scrape_target_absent{target="x"} 1}），
 *       这样「抓不到」就能像别的指标一样被规则消费。</li>
 * </ol>
 *
 * <p>事件条数有上限（默认 500 条滚动保留）：事件是排障证据，不是流水账 ——
 * 无上限的内存增长在长期运行的收端上迟早出事，而超出窗口的老事件对「现在怎么样」没有影响。
 */
public final class ScrapeStatusRegistry {

    /** 事件保留条数上限。 */
    public static final int DEFAULT_MAX_EVENTS = 500;

    private final Map<String, ScrapeStatus> statuses =
            new LinkedHashMap<String, ScrapeStatus>();
    private final List<ScrapeEvent> events = new ArrayList<ScrapeEvent>();
    private final int maxEvents;

    private long failureCount;
    private long parseFailureCount;
    private long successCount;

    public ScrapeStatusRegistry() {
        this(DEFAULT_MAX_EVENTS);
    }

    public ScrapeStatusRegistry(int maxEvents) {
        if (maxEvents <= 0) {
            throw new IllegalArgumentException("事件保留条数必须为正");
        }
        this.maxEvents = maxEvents;
    }

    /**
     * 吃进一条事件：更新该 target 的状态，并把事件追加进滚动窗口。
     *
     * <p>状态与事件在同一个方法里落，是刻意的：状态和事件分开写就会出现
     * 「状态说有、事件没有」（或反过来）这种对不上的中间态，
     * 而排障时人只会看其中一处。
     */
    public synchronized void record(ScrapeEvent event) {
        if (event == null) {
            return;
        }
        ScrapeStatus status = statuses.get(event.targetId());
        if (status == null) {
            status = new ScrapeStatus(event.targetId());
            statuses.put(event.targetId(), status);
        }
        status.apply(event);
        switch (event.type()) {
            case SCRAPE_SUCCEEDED:
                successCount++;
                break;
            case SCRAPE_FAILED:
                failureCount++;
                break;
            case PARSE_FAILED:
                parseFailureCount++;
                break;
            default:
                break;
        }
        events.add(event);
        while (events.size() > maxEvents) {
            events.remove(0);
        }
    }

    /** 某个 target 的状态；没有就 null。 */
    public synchronized ScrapeStatus status(String targetId) {
        return statuses.get(targetId);
    }

    /** 全部 target 的状态快照（拷贝，后续轮次不会改写它）。 */
    public synchronized List<ScrapeStatus> snapshot() {
        List<ScrapeStatus> out = new ArrayList<ScrapeStatus>(statuses.size());
        for (ScrapeStatus s : statuses.values()) {
            out.add(new ScrapeStatus(s));
        }
        return out;
    }

    /** 现在缺席（抓不到）的 target id 列表 —— absent 规则要的正是这个。 */
    public synchronized List<String> absentTargets() {
        List<String> out = new ArrayList<String>();
        for (ScrapeStatus s : statuses.values()) {
            if (s.isAbsent()) {
                out.add(s.targetId());
            }
        }
        return out;
    }

    /** 滚动窗口里的事件（新的在后）。 */
    public synchronized List<ScrapeEvent> events() {
        return new ArrayList<ScrapeEvent>(events);
    }

    /** 某段时间内的事件（新的在后）。 */
    public synchronized List<ScrapeEvent> eventsSince(long sinceMillis) {
        List<ScrapeEvent> out = new ArrayList<ScrapeEvent>();
        for (ScrapeEvent e : events) {
            if (e.timestampMillis() >= sinceMillis) {
                out.add(e);
            }
        }
        return out;
    }

    public synchronized long failureCount() {
        return failureCount;
    }

    public synchronized long parseFailureCount() {
        return parseFailureCount;
    }

    public synchronized long successCount() {
        return successCount;
    }

    /**
     * 把采集状态渲染成 Prometheus 文本（text format 0.0.4）。
     *
     * <p>用 core 那套 writer 渲染，而不是自己拼字符串： exposition 的格式只有一处定义，
     * 两处各拼一遍迟早漂移（转义规则、类型名、排序）。
     */
    public synchronized String toExposition() {
        MetricRegistry registry = new MetricRegistry();
        Labels none = Labels.empty();
        for (ScrapeStatus s : statuses.values()) {
            Labels labels = Labels.builder().put("target", s.targetId()).build();
            registry.gauge("z_observer_scrape_target_up",
                    "采集目标最近一轮是否抓得到（1=抓得到，0=抓不到）", labels)
                    .set(s.isUp() ? 1d : 0d);
            registry.gauge("z_observer_scrape_target_absent",
                    "采集目标当前是否缺席（1=抓不到，供 absent 规则消费）", labels)
                    .set(s.isAbsent() ? 1d : 0d);
            registry.gauge("z_observer_scrape_target_consecutive_failures",
                    "采集目标连续抓不到次数", labels).set(s.consecutiveFailures());
        }
        registry.counter("z_observer_scrape_failures_total", "抓取失败累计次数", none)
                .inc(failureCount);
        registry.counter("z_observer_scrape_parse_failures_total", "解析失败累计次数", none)
                .inc(parseFailureCount);
        registry.counter("z_observer_scrape_success_total", "抓取成功累计次数", none)
                .inc(successCount);
        return new PrometheusTextWriter(registry).render();
    }

    @Override
    public synchronized String toString() {
        return "ScrapeStatusRegistry{targets=" + statuses.size()
                + ", absent=" + absentTargets().size()
                + ", failures=" + failureCount
                + ", parseFailures=" + parseFailureCount + "}";
    }

    /** 只读快照，供调用方一次性拿走（避免边遍历边被改）。 */
    public static final class Snapshot {

        private final List<ScrapeStatus> statuses;
        private final List<ScrapeEvent> events;

        Snapshot(List<ScrapeStatus> statuses, List<ScrapeEvent> events) {
            this.statuses = Collections.unmodifiableList(statuses);
            this.events = Collections.unmodifiableList(events);
        }

        public List<ScrapeStatus> statuses() {
            return statuses;
        }

        public List<ScrapeEvent> events() {
            return events;
        }
    }
}
