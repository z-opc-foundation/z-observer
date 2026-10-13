package com.zifang.z.observer.admin.store;

import com.zifang.z.observer.core.model.SpanData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 链路存储：traceId -> span 列表。
 *
 * <p><b>存平表，树在查询时组。</b> span 乱序到达在真实调用里是常态（父 span 必须等
 * 子 span 结束才上报、异步链路更是隔了几秒），存树就得处理「子先到、父后到」的插入，
 * 并发改起树还会把整棵树卡在全局锁上。平表写入就是加一条，谁先到都不影响别人。
 *
 * <p>保留策略与日志同构：条数上限 + 时长上限。链路的自然单位是「条 trace」而不是
 * 「条 span」——一条大链路的 span 数是小链路的几十倍，只按 span 数淘汰的话，
 * 会被少数超大链路挤掉整个时间窗。
 */
public final class TraceStore {

    public static final int DEFAULT_MAX_TRACES = 20_000;
    public static final long DEFAULT_RETENTION_MILLIS = 2 * 60 * 60 * 1000L;
    /** 单条 trace 的 span 上限。超出部分截断并计数，不能让它无界长。 */
    public static final int MAX_SPANS_PER_TRACE = 5000;

    private final int maxTraces;
    private final long retentionMillis;
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicLong truncated = new AtomicLong();

    // 声明在 evicted 之后：removeEldestEntry 是匿名子类的方法，读的是外层字段，
    // 顺序放错的话字段还是 null（虽然实际 put 发生在构造之后，但依赖这一点很脆）。
    private final Map<String, List<SpanData>> byTrace =
            new LinkedHashMap<String, List<SpanData>>(256, 0.75f, false) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<SpanData>> eldest) {
                    boolean tooMany = size() > maxTraces;
                    if (tooMany) {
                        evicted.incrementAndGet();
                    }
                    return tooMany;
                }
            };

    private long newestSeenMillis;

    public TraceStore() {
        this(DEFAULT_MAX_TRACES, DEFAULT_RETENTION_MILLIS);
    }

    public TraceStore(int maxTraces, long retentionMillis) {
        if (maxTraces <= 0) {
            throw new IllegalArgumentException("maxTraces 必须为正: " + maxTraces);
        }
        if (retentionMillis <= 0) {
            throw new IllegalArgumentException("retentionMillis 必须为正: " + retentionMillis);
        }
        this.maxTraces = maxTraces;
        this.retentionMillis = retentionMillis;
    }

    public synchronized void append(SpanData span) {
        if (span == null) {
            throw new IllegalArgumentException("span 不能为 null");
        }
        List<SpanData> list = byTrace.get(span.traceId());
        if (list == null) {
            list = new ArrayList<SpanData>();
            byTrace.put(span.traceId(), list);
        }
        // 同一个 spanId 重复到达（SDK 重试 / 双实例同 id）以先到的为准：
        // 后到的可能是半成品（未结束时抓的快照），覆盖会把完整数据降级成残缺数据。
        for (SpanData existing : list) {
            if (existing.spanId().equals(span.spanId())) {
                return;
            }
        }
        if (list.size() >= MAX_SPANS_PER_TRACE) {
            truncated.incrementAndGet();
            return;
        }
        list.add(span);

        long ts = span.startTimeMillis() > 0
                ? span.startTimeMillis() : System.currentTimeMillis();
        if (ts > newestSeenMillis) {
            newestSeenMillis = ts;
        }
        evictByAge();
    }

    public synchronized void appendAll(List<SpanData> spans) {
        if (spans != null) {
            for (SpanData s : spans) {
                append(s);
            }
        }
    }

    private void evictByAge() {
        long cutoff = newestSeenMillis - retentionMillis;
        for (java.util.Iterator<Map.Entry<String, List<SpanData>>> it = byTrace.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<String, List<SpanData>> e = it.next();
            long newest = 0;
            for (SpanData s : e.getValue()) {
                if (s.startTimeMillis() > newest) {
                    newest = s.startTimeMillis();
                }
            }
            if (newest > 0 && newest < cutoff) {
                it.remove();
                evicted.incrementAndGet();
            }
        }
    }

    /** 取一条链路的原始 span（按开始时间升序）。 */
    public synchronized List<SpanData> spans(String traceId) {
        List<SpanData> list = byTrace.get(traceId);
        if (list == null) {
            return Collections.emptyList();
        }
        List<SpanData> copy = new ArrayList<SpanData>(list);
        Collections.sort(copy, BY_START);
        return copy;
    }

    /**
     * 取链路树。
     *
     * <p>孤儿 span（父不在本地）挂在根上并标注 orphan，而不是丢弃：跨服务链路里
     * 上游的 span 在别的进程，丢掉下半条链路比标个「我缺父」有用得多。
     */
    public synchronized TraceTree trace(String traceId) {
        List<SpanData> all = spans(traceId);
        if (all.isEmpty()) {
            return null;
        }
        Map<String, TraceNode> nodes = new HashMap<String, TraceNode>();
        for (SpanData s : all) {
            nodes.put(s.spanId(), new TraceNode(s, null));
        }
        List<TraceNode> roots = new ArrayList<TraceNode>();
        int orphans = 0;
        for (TraceNode n : nodes.values()) {
            String parentId = n.span().parentSpanId();
            TraceNode parent = parentId == null || parentId.isEmpty()
                    ? null : nodes.get(parentId);
            if (parent == null) {
                if (parentId != null && !parentId.isEmpty()) {
                    orphans++;
                }
                roots.add(n);
            } else {
                parent.children().add(n);
            }
        }
        Collections.sort(roots, BY_NODE_START);
        for (TraceNode n : nodes.values()) {
            Collections.sort(n.children(), BY_NODE_START);
        }
        return new TraceTree(traceId, roots, all.size(), orphans);
    }

    public synchronized long evictedCount() {
        return evicted.get();
    }

    public synchronized long truncatedCount() {
        return truncated.get();
    }

    public synchronized int traceCount() {
        return byTrace.size();
    }

    /** 按最近活跃排序的 traceId 列表，看板「最近链路」用。 */
    public synchronized List<String> recentTraceIds(int limit) {
        List<String> ids = new ArrayList<String>(byTrace.keySet());
        Collections.reverse(ids);
        if (ids.size() > limit) {
            return new ArrayList<String>(ids.subList(0, limit));
        }
        return ids;
    }

    private static final Comparator<TraceNode> BY_NODE_START = new Comparator<TraceNode>() {
        @Override
        public int compare(TraceNode a, TraceNode b) {
            long x = a.span().startTimeMillis();
            long y = b.span().startTimeMillis();
            return x == y ? 0 : (x < y ? -1 : 1);
        }
    };

    /** 树上的一个节点。 */
    public static final class TraceNode {
        private final SpanData span;
        private final List<TraceNode> children = new ArrayList<TraceNode>();

        TraceNode(SpanData span, List<TraceNode> ignored) {
            this.span = span;
        }

        public SpanData span() {
            return span;
        }

        public List<TraceNode> children() {
            return children;
        }
    }

    /** 一条链路的树形视图。 */
    public static final class TraceTree {
        private final String traceId;
        private final List<TraceNode> roots;
        private final int spanCount;
        private final int orphanCount;

        TraceTree(String traceId, List<TraceNode> roots, int spanCount, int orphanCount) {
            this.traceId = traceId;
            this.roots = roots;
            this.spanCount = spanCount;
            this.orphanCount = orphanCount;
        }

        public String traceId() {
            return traceId;
        }

        public List<TraceNode> roots() {
            return roots;
        }

        public int spanCount() {
            return spanCount;
        }

        /**
         * 父 span 不在本地的条数。跨服务链路里这是常态（上游在别的进程），
         * 但数值突然变大意味着上游服务没接上 SDK 或者采样断了 —— 值得单独暴露。
         */
        public int orphanCount() {
            return orphanCount;
        }
    }

    private static final Comparator<SpanData> BY_START = new Comparator<SpanData>() {
        @Override
        public int compare(SpanData a, SpanData b) {
            long x = a.startTimeMillis();
            long y = b.startTimeMillis();
            return x == y ? 0 : (x < y ? -1 : 1);
        }
    };
}
