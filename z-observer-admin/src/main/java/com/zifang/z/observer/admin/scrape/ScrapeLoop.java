package com.zifang.z.observer.admin.scrape;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一轮采集：发现 → 登记 → 逐个拉。
 *
 * <p><b>发现失败时保留上一轮的 target 集合</b>，这一轮只记失败、不采任何东西。
 * 反过来做（把空集合当成「全挂了」）会一次性造出一片 absent 告警 ——
 * 发现源自己抖一下，整集群看起来全灭了。这比漏报危险得多。
 *
 * <p>单个 target 拉失败不中断本轮：采不动的那个由规则按 absent 报出来，
 * 其余 target 的数据照常入库。
 */
public final class ScrapeLoop {

    private static final Logger LOG = LogManager.getLogger(ScrapeLoop.class);

    private final TargetSource source;
    private final TargetRegistry registry;
    private final Scraper scraper;

    private final AtomicLong discoverFailureCount = new AtomicLong();
    private final List<String> lastErrors = new ArrayList<String>();

    public ScrapeLoop(TargetSource source, TargetRegistry registry, Scraper scraper) {
        if (source == null || registry == null || scraper == null) {
            throw new IllegalArgumentException("采集三件套必填");
        }
        this.source = source;
        this.registry = registry;
        this.scraper = scraper;
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
            return Round.discoverFailed(registry, e.getMessage());
        }

        List<ScrapeTarget> newlyVanished = registry.refresh(discovered, nowMillis);
        for (ScrapeTarget t : newlyVanished) {
            // 这条就是「进程可能挂了」的现场，留痕比只计数有用
            LOG.warn("target {} 从 [{}] 的发现结果中消失", t.id(), source.name());
        }

        Round round = new Round(registry, newlyVanished);
        for (ScrapeTarget t : registry.live()) {
            Scraper.Result r = scraper.scrape(t);
            if (r.isSuccess()) {
                round.ok.put(t.id(), r.text());
                round.okCount.incrementAndGet();
            } else {
                round.errors.add(t.id() + ": " + r.error());
                round.failCount.incrementAndGet();
            }
        }
        return round;
    }

    public long discoverFailureCount() {
        return discoverFailureCount.get();
    }

    public TargetRegistry registry() {
        return registry;
    }

    /** 一轮的结果。 */
    public static final class Round {
        private final TargetRegistry registry;
        private final List<ScrapeTarget> newlyVanished;
        private final java.util.Map<String, String> ok =
                new java.util.LinkedHashMap<String, String>();
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
        public java.util.Map<String, String> samples() {
            return ok;
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