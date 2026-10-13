package com.zifang.z.observer.admin.scrape;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetRegistryTest {

    private static List<ScrapeTarget> targets(String... ids) {
        List<ScrapeTarget> out = new ArrayList<ScrapeTarget>();
        for (String id : ids) {
            out.add(new ScrapeTarget(id, "http://" + id + ":9100/metrics"));
        }
        return out;
    }

    @Test
    void 首次发现只登记不算消失() {
        TargetRegistry r = new TargetRegistry();
        List<ScrapeTarget> vanished = r.refresh(targets("a", "b"), 1000L);
        assertThat(vanished).isEmpty();
        assertThat(r.live()).hasSize(2);
        assertThat(r.vanished()).isEmpty();
    }

    @Test
    void 实例从发现结果里消失要留下记录_否则absent永远等不到它() {
        TargetRegistry r = new TargetRegistry();
        r.refresh(targets("a", "b"), 1000L);

        // 进程挂了 → naming 注销 → 下一轮只剩 b
        List<ScrapeTarget> vanished = r.refresh(targets("b"), 2000L);

        assertThat(vanished).hasSize(1);
        assertThat(vanished.get(0).id()).isEqualTo("a");
        assertThat(r.isVanished("a")).isTrue();
        assertThat(r.liveCount()).isEqualTo(1);
    }

    @Test
    void 实例回来要销掉消失记录() {
        TargetRegistry r = new TargetRegistry();
        r.refresh(targets("a"), 1000L);
        r.refresh(Collections.<ScrapeTarget>emptyList(), 2000L);
        assertThat(r.isVanished("a")).isTrue();

        List<ScrapeTarget> revived = r.refresh(targets("a"), 3000L);

        assertThat(revived).isEmpty();
        assertThat(r.isVanished("a")).isFalse();
        assertThat(r.vanishedCount()).isZero();
    }

    @Test
    void 反复抖动的发现源不会反复报消失() {
        TargetRegistry r = new TargetRegistry();
        r.refresh(targets("a", "b"), 1000L);
        assertThat(r.refresh(targets("a"), 2000L)).hasSize(1);
        // 又消失一次：同一轮发现结果抖动，不是新的消失事件
        assertThat(r.refresh(targets("a"), 3000L)).isEmpty();
        assertThat(r.isVanished("b")).isTrue();
    }

    @Test
    void 消失超过保留期就清掉_不留幽灵记录() {
        TargetRegistry r = new TargetRegistry(1000L);
        r.refresh(targets("a"), 1000L);
        r.refresh(Collections.<ScrapeTarget>emptyList(), 2000L);
        assertThat(r.vanishedCount()).isEqualTo(1);

        r.refresh(Collections.<ScrapeTarget>emptyList(), 3100L);

        assertThat(r.vanishedCount()).isZero();
    }

    @Test
    void 静态target源返回配置里的那份() {
        StaticTargetSource s = StaticTargetSource.ofUrls(
                "http://10.0.0.1:9100/metrics", "http://10.0.0.2:9100/metrics");
        assertThat(s.name()).isEqualTo("static");
        assertThat(s.discover()).hasSize(2);
        assertThatThrownBy(() -> new StaticTargetSource(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 静态target源支持host与port成对给出() {
        StaticTargetSource s = StaticTargetSource.ofUrlsAndPorts(
                "10.0.0.1", "9100", "10.0.0.2", "9101");
        assertThat(s.discover().get(0).id()).isEqualTo("10.0.0.1:9100");
        assertThat(s.discover().get(0).url()).isEqualTo("http://10.0.0.1:9100/metrics");
        assertThatThrownBy(() -> StaticTargetSource.ofUrlsAndPorts("10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 发现源列表不被外部改动影响() {
        List<ScrapeTarget> src = new ArrayList<ScrapeTarget>(
                Arrays.asList(new ScrapeTarget("a", "http://a:9100/metrics")));
        StaticTargetSource s = new StaticTargetSource(src);
        src.clear();
        assertThat(s.discover()).hasSize(1);
    }
}