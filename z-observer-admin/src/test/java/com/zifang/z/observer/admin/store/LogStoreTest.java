package com.zifang.z.observer.admin.store;

import com.zifang.z.observer.core.model.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogStoreTest {

    private static LogEvent at(long ts, String level, String message, String traceId) {
        LogEvent.Builder b = LogEvent.builder()
                .timestampMillis(ts)
                .level(level)
                .logger("com.demo.Foo")
                .thread("main")
                .message(message);
        if (traceId != null) {
            b.traceId(traceId);
        }
        return b.build();
    }

    @Test
    void 写入后能按中文关键词检索到() {
        LogStore store = new LogStore();
        store.append(at(1000, "INFO", "订单创建成功", null));
        store.append(at(1001, "ERROR", "订单创建失败: 数据库连接超时", null));
        store.append(at(1002, "INFO", "用户登录成功", null));

        List<LogEvent> hits = store.search(LogQuery.builder().text("失败").build());
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).message()).contains("数据库连接超时");
    }

    @Test
    void 多关键词之间是且的关系() {
        LogStore store = new LogStore();
        store.append(at(1000, "ERROR", "订单创建失败", null));
        store.append(at(1001, "ERROR", "订单创建失败重试成功", null));
        store.append(at(1002, "ERROR", "支付失败", null));

        assertThat(store.search(LogQuery.builder().text("订单 失败").build())).hasSize(2);
        assertThat(store.search(LogQuery.builder().text("订单 重试").build())).hasSize(1);
    }

    @Test
    void 按级别和时间窗过滤() {
        LogStore store = new LogStore();
        store.append(at(1000, "INFO", "a", null));
        store.append(at(2000, "ERROR", "b", null));
        store.append(at(3000, "ERROR", "c", null));

        assertThat(store.search(LogQuery.builder().level("ERROR").build())).hasSize(2);
        assertThat(store.search(LogQuery.builder()
                .fromMillis(2000).toMillis(2500).build())).hasSize(1);
    }

    @Test
    void 按traceId聚类能捞出整条链路的日志() {
        LogStore store = new LogStore();
        store.append(at(1000, "INFO", "开始", "trace-1"));
        store.append(at(1001, "ERROR", "炸了", "trace-1"));
        store.append(at(1002, "INFO", "无关", "trace-2"));

        assertThat(store.search(LogQuery.builder().traceId("trace-1").build())).hasSize(2);
    }

    @Test
    void 结果按时间倒序并受limit约束() {
        LogStore store = new LogStore();
        store.append(at(1000, "INFO", "oldest", null));
        store.append(at(3000, "INFO", "newest", null));
        store.append(at(2000, "INFO", "middle", null));

        List<LogEvent> hits = store.search(LogQuery.builder().limit(2).build());
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).message()).isEqualTo("newest");
        assertThat(hits.get(1).message()).isEqualTo("middle");
    }

    @Test
    void 超过条数上限后最老的被淘汰且计数可见() {
        LogStore store = new LogStore(3, Long.MAX_VALUE);
        for (int i = 0; i < 5; i++) {
            store.append(at(1000 + i, "INFO", "msg-" + i, null));
        }

        assertThat(store.size()).isEqualTo(3);
        assertThat(store.evictedCount()).isEqualTo(2);
        // 淘汰掉的不能再被检索到 —— 否则会返回一条已经查不到内容的幽灵记录。
        assertThat(store.search(LogQuery.builder().text("msg-0").build())).isEmpty();
        assertThat(store.search(LogQuery.builder().text("msg-4").build())).hasSize(1);
    }

    @Test
    void 超过时长上限后旧的被淘汰() {
        // cutoff = 最新事件(1200) - 保留时长(150) = 1050，只有 1000 那条早于它
        LogStore store = new LogStore(1000, 150);
        store.append(at(1000, "INFO", "old", null));
        store.append(at(1050, "INFO", "recent", null));
        store.append(at(1200, "INFO", "newest", null));

        assertThat(store.evictedCount()).isEqualTo(1);
        assertThat(store.search(LogQuery.builder().text("old").build())).isEmpty();
        assertThat(store.search(LogQuery.builder().text("recent").build())).hasSize(1);
    }

    @Test
    void 保留时长按最新事件算而不是墙钟() {
        // 三条日志的业务时间都远早于「现在」，但彼此只差 10ms：
        // 按墙钟判会三条全被判过期，一次性清空。
        LogStore store = new LogStore(1000, 1000);
        long base = System.currentTimeMillis() - 86_400_000L;
        store.append(at(base, "INFO", "a", null));
        store.append(at(base + 10, "INFO", "b", null));
        store.append(at(base + 20, "INFO", "c", null));

        assertThat(store.size()).isEqualTo(3);
        assertThat(store.evictedCount()).isZero();
    }

    @Test
    void 淘汰之后倒排索引不留空洞() {
        LogStore store = new LogStore(2, Long.MAX_VALUE);
        store.append(at(1000, "INFO", "alpha", null));
        store.append(at(1001, "INFO", "beta", null));
        store.append(at(1002, "INFO", "gamma", null));

        // alpha 已淘汰，但它的词元还留在倒排表里（墓碑）。
        // 若查询不过存活表就会把 alpha 当成命中返回。
        assertThat(store.search(LogQuery.builder().text("alpha").build())).isEmpty();
    }

    @Test
    void 重复词不会让同一篇文档重复命中() {
        LogStore store = new LogStore();
        store.append(at(1000, "INFO", "订单订单订单", null));
        assertThat(store.search(LogQuery.builder().text("订单").build())).hasSize(1);
    }

    @Test
    void 无全文条件时返回全部存活日志() {
        LogStore store = new LogStore();
        store.appendAll(java.util.Arrays.asList(
                at(1000, "INFO", "a", null),
                at(1001, "INFO", "b", null)));
        assertThat(store.search(LogQuery.builder().build())).hasSize(2);
    }

    @Test
    void limit有硬上限() {
        LogQuery q = LogQuery.builder().limit(999999).build();
        assertThat(q.limit()).isEqualTo(1000);
    }

    @Test
    void 时间窗颠倒直接报错() {
        assertThatThrownBy(() -> LogQuery.builder()
                .fromMillis(2000).toMillis(1000).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void MDC字段可以按值过滤() {
        LogStore store = new LogStore();
        store.append(LogEvent.builder()
                .timestampMillis(1000).level("INFO").message("下单")
                .fields(Collections.singletonMap("orderId", "42")).build());
        store.append(LogEvent.builder()
                .timestampMillis(1001).level("INFO").message("下单")
                .fields(Collections.singletonMap("orderId", "43")).build());

        assertThat(store.search(LogQuery.builder().field("orderId", "42").build())).hasSize(1);
    }
}
