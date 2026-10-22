package com.zifang.z.observer.admin.scrape;

import com.zifang.z.observer.core.metric.PrometheusTextParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一轮采集：发现 → 登记 → 逐个拉 → 解析 → 记事件。
 *
 * <p><b>发现失败时保留上一轮的 target 集合</b>，这一轮只记失败、不采任何东西。
 * 反过来做（把空集合当成「全挂了」）会一次性造出一片 absent 告警 ——
 * 发现源自己抖一下，整集群看起来全灭了。这比漏报危险得多。
 *
 * <p>单个 target 拉失败不中断本轮：采不动的那个由规则按 absent 报出来，
 * 其余 target 的数据照常入库。
 *
 * <p><b>解析失败按失败论</b>（不是「抓到了就算好的」）：exposition 里混进坏行时，
 * 那一轮的数据是不完整的；把它当成功入库，等于用半截数据覆盖了上一轮的好数据，
 * 而面板上看不出任何异常。判据是<b>这一轮对这个 target 有没有拿到「可用的全量」</b>。
 *
 * <p>每一轮的结论都落进 {@link ScrapeStatusRegistry}：抓不到 / 抓到了 / 解析坏了
 * 都是可查询的事件，absent 规则消费的就是它（见 {@link ScrapeStatusRegistry}）。
 */
public final class ScrapeLoop {

    private static final Logger LOG = LogManager.getLogger(ScrapeLoop.class);

    private final TargetSource source;
    private final TargetRegistry registry;
    private final Scraper scraper;
    private final PrometheusTextParser parser;
    private final ScrapeStatusRegistry status;

    private final AtomicLong discoverFailureCount = new AtomicLong();
    private final List<String> lastErrors = new ArrayList<String>();

    public ScrapeLoop(TargetSource source, TargetRegistry registry, Scraper scraper) {
        this(source, registry, scraper, new PrometheusTextParser(), new ScrapeStatusRegistry());
    }

    public ScrapeLoop(TargetSource source, TargetRegistry registry, Scraper scraper,
                      PrometheusTextParser parser, ScrapeStatusRegistry status) {
        if (source == null || registry == null || scraper == null
                || parser == null || status == null) {
            throw new IllegalArgumentException("采集三件套必填");
        }
        this.source = source;
        this.registry = registry;
        this.scraper = scraper;
        this.parser = parser;
        this.status = status;
    }

    /** 跑一轮，返回本轮采到的 exposition（target id → 正文）。 */
    public Round run(long nowMillis) {
        List<ScrapeTarget> discovered;
        try {
            discovered = source.discover();
        } catch (RuntimeException e) {
            discoverFailureCount.incrementAndGet();
            LOG.warn("[{}] target 发现失败，本轮保留上一轮集合: {}",
                    source.name(), e.getMessage());
            Round failed = Round.discoverFailed(registry, e.getMessage());
            // ⚠ 发现源故障**不**记成某个 target 的缺席：
            //   「z-config 自己挂了」是采集侧的故障，不是「这些 target 挂了」——
            //   把它算成缺席会一次性造出全集群的 absent 告警（比不告警危险得多）。
            //   它的可见性走 discoverFailureCount() 与本轮的 errors。
            return failed;
        }

        // refresh 之前先记住「上一轮谁消失了」：refresh 会把归来的 target 从消失名单里抹掉，
        // 想给「又回来了」记一条事件就得趁它还在名单里问一句。
        List<String> vanishedBefore = new ArrayList<String>();
        for (ScrapeTarget t : registry.vanished()) {
            vanishedBefore.add(t.id());
        }

        List<ScrapeTarget> newlyVanished = registry.refresh(discovered, nowMillis);
        for (ScrapeTarget t : newlyVanished) {
            // 这条就是「进程可能挂了」的现场，留痕比只计数有用
            LOG.warn("target {} 从 [{}] 的发现结果中消失", t.id(), source.name());
            status.record(ScrapeEvent.vanished(t.id(), nowMillis));
        }
        for (ScrapeTarget t : registry.live()) {
            if (vanishedBefore.contains(t.id())) {
                status.record(ScrapeEvent.reappeared(t.id(), nowMillis));
            }
        }

        Round round = new Round(registry, newlyVanished);
        for (ScrapeTarget t : registry.live()) {
            Scraper.Result r = scraper.scrape(t);
            if (!r.isSuccess()) {
                status.record(ScrapeEvent.failed(t.id(), nowMillis, r.error()));
                round.errors.add(t.id() + ": " + r.error());
                round.failCount.incrementAndGet();
                continue;
            }
            PrometheusTextParser.ParseResult parsed = parser.parse(r.text());
            if (parsed.hasErrors()) {
                String reason = parsed.errorCount() + " 行解析失败，第一处："
                        + parsed.errors().get(0).toString();
                LOG.warn("target {} 的 exposition 解析有坏行: {}", t.id(), reason);
                status.record(ScrapeEvent.parseFailed(t.id(), nowMillis, reason));
                round.errors.add(t.id() + ": " + reason);
                round.failCount.incrementAndGet();
                continue;
            }
            status.record(ScrapeEvent.succeeded(t.id(), nowMillis, parsed.sampleCount()));
            round.ok.put(t.id(), r.text());
            round.parsed.put(t.id(), parsed.samples());
            round.okCount.incrementAndGet();
        }
        return round;
    }

    public long discoverFailureCount() {
        return discoverFailureCount.get();
    }

    public TargetRegistry registry() {
        return registry;
    }

    /** 采集状态登记处（absent 数据源）。 */
    public ScrapeStatusRegistry status() {
        return status;
    }

    /** 一轮的结果。 */
    public static final class Round {

        private final TargetRegistry registry;
        private final List<ScrapeTarget> newlyVanished;
        private final Map<String, String> ok = new LinkedHashMap<String, String>();
        private final Map<String, List<PrometheusTextParser.ParsedSample>> parsed =
                new LinkedHashMap<String, List<PrometheusTextParser.ParsedSample>>();
        private final List<String> errors = new ArrayList<String>();
        private final AtomicLong okCount = new AtomicLong();
        private final AtomicLong failCount = new AtomicLong();
        private boolean discoverFailed;

        Round(TargetRegistry registry, List<ScrapeTarget> newlyVanished) {
            this.registry = registry;
            this.newlyVanished = newlyVanished;
        }

        static Round discoverFailed(TargetRegistry registry, String error) {
            Round r = new Round(registry, new ArrayList<ScrapeTarget>());
            r.discoverFailed = true;
            r.errors.add("target 发现失败: " + error);
            return r;
        }

        /** 本轮新消失的 target：规则引擎拿它们发 absent 告警。 */
        public List<ScrapeTarget> newlyVanished() {
            return newlyVanished;
        }

        /** 采集到正文的 target。 */
        public Map<String, String> samples() {
            return ok;
        }

        /** 解析出来的样本（target id → 样本列表）。解析失败的 target 不在这里面。 */
        public Map<String, List<PrometheusTextParser.ParsedSample>> parsedSamples() {
            return parsed;
        }

        public List<String> errors() {
            return errors;
        }

        public int okCount() {
            return (int) okCount.get();
        }

        public int failCount() {
            return (int) failCount.get();
        }

        public boolean isDiscoverFailed() {
            return discoverFailed;
        }
    }
}
