package com.zifang.z.observer.admin.scrape;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集层验收通路的<b>短跑版</b>：常规构建里跑的就是这个（十几秒）。
 *
 * <p>完整 10 分钟那场在 {@link ScrapeAcceptanceHarness}（类名不匹配 surefire 默认包含规则，
 * 需要显式 {@code -Dtest=ScrapeAcceptanceHarness} 才跑）。这里跑同一条通路：
 * 5 个 target → 连续抓 → 杀掉一个 → 抓不到事件 → 缺席名单 → 恢复。
 *
 * <p><b>为什么常规构建里也要跑一遍</b>：验收脚本本身会腐坏 ——
 * 它验证的是「杀掉 target 之后采集层会不会说抓不到」，而这条断言正是最容易被
 * 改坏又最不容易被察觉的地方（改坏了不会报错，只会让 absent 告警永远不响）。
 */
class ScrapeAcceptanceTest {

    @Test
    void 连续抓取解析零失败_杀掉一个target下一轮产生抓不到事件() throws Exception {
        ScrapeAcceptanceHarness.Result r = ScrapeAcceptanceHarness.run(
                12_000L, 3_000L,
                Paths.get("target/scrape-acceptance-short"));

        assertThat(r.parseFailures).isZero();
        assertThat(r.okScrapes).isPositive();
        assertThat(r.parsedSamples).isPositive();
        assertThat(r.allTargetsUpBeforeKill).isTrue();
        assertThat(r.killObserved).isTrue();
        assertThat(r.recoveryObserved).isTrue();
        assertThat(r.absentRounds).isNotEmpty();
    }
}
