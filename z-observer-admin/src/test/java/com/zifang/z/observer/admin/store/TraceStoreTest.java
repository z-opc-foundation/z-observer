package com.zifang.z.observer.admin.store;

import com.zifang.z.observer.core.model.SpanData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceStoreTest {

    private static SpanData span(String traceId, String spanId, String parentId,
                                 String name, long start, long end) {
        return SpanData.builder()
                .traceId(traceId).spanId(spanId).parentSpanId(parentId)
                .name(name).startTimeMillis(start).endTimeMillis(end)
                .build();
    }

    @Test
    void 按traceId聚出完整链路() {
        TraceStore store = new TraceStore();
        store.append(span("t1", "s1", null, "GET /order", 1000, 1500));
        store.append(span("t1", "s2", "s1", "db.query", 1100, 1200));
        store.append(span("t1", "s3", "s2", "db.scan", 1150, 1190));
        store.append(span("t2", "s9", null, "GET /other", 1000, 1100));

        TraceStore.TraceTree tree = store.trace("t1");
        assertThat(tree).isNotNull();
        assertThat(tree.spanCount()).isEqualTo(3);
        assertThat(tree.roots()).hasSize(1);
        TraceStore.TraceNode root = tree.roots().get(0);
        assertThat(root.span().name()).isEqualTo("GET /order");
        assertThat(root.children()).hasSize(1);
        assertThat(root.children().get(0).children()).hasSize(1);
        assertThat(root.children().get(0).children().get(0).span().name()).isEqualTo("db.scan");
    }

    @Test
    void 子span先到也不影响组树() {
        TraceStore store = new TraceStore();
        // 真实调用里子 span 先结束、先上报是常态
        store.append(span("t1", "s3", "s2", "db.scan", 1150, 1190));
        store.append(span("t1", "s2", "s1", "db.query", 1100, 1200));
        store.append(span("t1", "s1", null, "GET /order", 1000, 1500));

        TraceStore.TraceTree tree = store.trace("t1");
        assertThat(tree.roots()).hasSize(1);
        assertThat(tree.roots().get(0).span().spanId()).isEqualTo("s1");
        assertThat(tree.orphanCount()).isZero();
    }

    @Test
    void 父span在别的进程时算孤儿但仍保留() {
        TraceStore store = new TraceStore();
        store.append(span("t1", "s2", "upstream-span", "order/create", 1100, 1200));

        TraceStore.TraceTree tree = store.trace("t1");
        assertThat(tree.roots()).hasSize(1);
        assertThat(tree.orphanCount()).isEqualTo(1);
    }

    @Test
    void 重复spanId以先到的为准不被覆盖() {
        TraceStore store = new TraceStore();
        store.append(span("t1", "s1", null, "完整", 1000, 1500));
        // 后到的半成品（未结束时抓的快照）
        store.append(span("t1", "s1", null, "半成品", 1000, 0));

        List<SpanData> spans = store.spans("t1");
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).name()).isEqualTo("完整");
        assertThat(spans.get(0).durationMillis()).isEqualTo(500);
    }

    @Test
    void 不存在的trace返回null而不是空树() {
        assertThat(new TraceStore().trace("nope")).isNull();
    }

    @Test
    void 超过trace条数上限后淘汰最老的() {
        TraceStore store = new TraceStore(2, Long.MAX_VALUE);
        store.append(span("t1", "s", null, "a", 1000, 1100));
        store.append(span("t2", "s", null, "b", 1000, 1100));
        store.append(span("t3", "s", null, "c", 1000, 1100));

        assertThat(store.traceCount()).isEqualTo(2);
        assertThat(store.evictedCount()).isEqualTo(1);
        assertThat(store.trace("t1")).isNull();
        assertThat(store.trace("t3")).isNotNull();
    }

    @Test
    void 单条trace的span数有上限且计数可见() {
        TraceStore store = new TraceStore();
        for (int i = 0; i < TraceStore.MAX_SPANS_PER_TRACE + 10; i++) {
            store.append(span("t1", "s" + i, null, "op", 1000 + i, 1001 + i));
        }
        assertThat(store.spans("t1")).hasSize(TraceStore.MAX_SPANS_PER_TRACE);
        assertThat(store.truncatedCount()).isEqualTo(10);
    }

    @Test
    void 超过保留时长的链路被淘汰() {
        TraceStore store = new TraceStore(100, 100);
        store.append(span("t1", "s", null, "old", 1000, 1100));
        store.append(span("t1", "s2", null, "old2", 1050, 1100));
        store.append(span("t2", "s", null, "new", 1300, 1400));

        assertThat(store.traceCount()).isEqualTo(1);
        assertThat(store.trace("t1")).isNull();
    }
}
