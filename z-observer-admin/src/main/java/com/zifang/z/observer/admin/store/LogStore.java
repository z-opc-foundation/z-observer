package com.zifang.z.observer.admin.store;

import com.zifang.z.observer.core.model.LogEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志存储：环形保留 + 倒排检索。
 *
 * <p><b>两条独立的淘汰线。</b> 按条数淘汰管住内存占用（一条日志几 KB，条数是内存的
 * 直接线性因子）；按保留时长淘汰管住「查最近一小时」这个语义 —— 只按条数的话，
 * 低流量服务会被一场历史洪峰挤掉，昨天的故障现场查不到了。
 *
 * <p><b>淘汰是可见的。</b> 静默淘汰等于静默丢数据：用户查不到旧日志时不会想到
 * 「哦它被淘汰了」，只会以为系统没收到那条日志。所以淘汰数一直暴露在
 * {@link #evictedCount()}，并由收端暴露成指标。
 */
public final class LogStore {

    public static final int DEFAULT_MAX_DOCS = 200_000;
    public static final long DEFAULT_RETENTION_MILLIS = 24 * 60 * 60 * 1000L;

    private final int maxDocs;
    private final long retentionMillis;
    private final InvertedIndex index = new InvertedIndex();

    /** 环形缓冲：docId 单调递增，物理槽位取模。 */
    private LogEvent[] ring;
    /** 下一个要分配的 docId。 */
    private long nextDocId;
    /** 当前最老的存活 docId。 */
    private long startDocId;
    private int size;

    /** 见到的最大事件时间戳，作为保留时长的参考点。 */
    private long newestSeenMillis;

    private final AtomicLong evicted = new AtomicLong();

    public LogStore() {
        this(DEFAULT_MAX_DOCS, DEFAULT_RETENTION_MILLIS);
    }

    public LogStore(int maxDocs, long retentionMillis) {
        if (maxDocs <= 0) {
            throw new IllegalArgumentException("maxDocs 必须为正: " + maxDocs);
        }
        if (retentionMillis <= 0) {
            throw new IllegalArgumentException("retentionMillis 必须为正: " + retentionMillis);
        }
        this.maxDocs = maxDocs;
        this.retentionMillis = retentionMillis;
        this.ring = new LogEvent[Math.min(maxDocs, 1024)];
    }

    /** 写入一条，返回它的 docId。 */
    public synchronized long append(LogEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event 不能为 null");
        }
        if (nextDocId - startDocId >= ring.length) {
            if (ring.length < maxDocs) {
                grow();
            } else {
                // 环形回绕：马上要写的槽位正是最老那条的位置。必须先把它从索引里摘掉，
                // 否则索引还留着一条已经查不出来的文档号，倒排结果里会出现空洞，
                // 而 size 也会跟着虚涨到 maxDocs 之上、淘汰逻辑再也停不下来。
                dropOldest();
            }
        }
        long docId = nextDocId++;
        ring[(int) (docId % ring.length)] = event;
        size++;
        index.add(docId, termsOf(event));

        long ts = event.timestampMillis() > 0
                ? event.timestampMillis() : System.currentTimeMillis();
        if (ts > newestSeenMillis) {
            newestSeenMillis = ts;
        }
        evictByAge();
        return docId;
    }

    public synchronized void appendAll(List<LogEvent> events) {
        if (events != null) {
            for (LogEvent e : events) {
                append(e);
            }
        }
    }

    private void grow() {
        int newCap = Math.min(maxDocs, ring.length * 2);
        LogEvent[] grown = new LogEvent[newCap];
        for (long id = startDocId; id < nextDocId; id++) {
            grown[(int) (id % newCap)] = ring[(int) (id % ring.length)];
        }
        ring = grown;
    }

    private void dropOldest() {
        int slot = (int) (startDocId % ring.length);
        if (ring[slot] != null) {
            ring[slot] = null;
            size--;
            evicted.incrementAndGet();
        }
        index.remove(startDocId);
        startDocId++;
    }

    /**
     * 按保留时长淘汰。
     *
     * <p>参考点是「见过的最新事件时间」而不是墙钟：新上报的日志带的是业务侧的时钟，
     * 拿墙钟判会把「客户端时钟慢」的日志立刻判成过期。
     */
    private void evictByAge() {
        if (newestSeenMillis <= 0) {
            return;
        }
        long cutoff = newestSeenMillis - retentionMillis;
        while (size > 0) {
            LogEvent oldest = ring[(int) (startDocId % ring.length)];
            if (oldest == null) {
                return;
            }
            long ts = oldest.timestampMillis();
            if (ts <= 0 || ts >= cutoff) {
                return;
            }
            dropOldest();
        }
    }

    /** 一条日志进索引的词元：message 走分词，其余字段与 MDC 走精确。 */
    private static List<String> termsOf(LogEvent event) {
        List<String> terms = new ArrayList<String>(Analyzer.tokenize(event.message()));
        addExact(terms, "level", event.level());
        addExact(terms, "logger", event.logger());
        addExact(terms, "thread", event.thread());
        addExact(terms, "traceId", event.traceId());
        addExact(terms, "spanId", event.spanId());
        for (Map.Entry<String, String> e : event.fields().entrySet()) {
            addExact(terms, "field." + e.getKey(), e.getValue());
        }
        return terms;
    }

    private static void addExact(List<String> terms, String field, String value) {
        if (value != null && !value.isEmpty()) {
            terms.add(InvertedIndex.EXACT + field + InvertedIndex.EXACT + value);
        }
    }

    /** 检索。text 为空表示不按全文过滤，只按其余条件。 */
    public synchronized List<LogEvent> search(LogQuery query) {
        Set<Long> candidates = null;
        if (query.text() != null && !query.text().trim().isEmpty()) {
            List<String> terms = Analyzer.tokenize(query.text());
            if (terms.isEmpty()) {
                return Collections.emptyList();
            }
            candidates = new HashSet<Long>(index.search(terms));
        }

        List<LogEvent> out = new ArrayList<LogEvent>();
        for (long id = startDocId; id < nextDocId; id++) {
            if (candidates != null && !candidates.contains(Long.valueOf(id))) {
                continue;
            }
            LogEvent e = ring[(int) (id % ring.length)];
            if (e != null && query.matches(e)) {
                out.add(e);
            }
        }
        // 默认按时间倒序：值班看的是最新发生了什么，不是一小时前的第一条。
        Collections.sort(out, BY_TIME_DESC);
        if (out.size() > query.limit()) {
            return new ArrayList<LogEvent>(out.subList(0, query.limit()));
        }
        return out;
    }

    public synchronized long evictedCount() {
        return evicted.get();
    }

    public synchronized int size() {
        return size;
    }

    private static final Comparator<LogEvent> BY_TIME_DESC = new Comparator<LogEvent>() {
        @Override
        public int compare(LogEvent a, LogEvent b) {
            long x = a.timestampMillis();
            long y = b.timestampMillis();
            return x == y ? 0 : (x > y ? -1 : 1);
        }
    };
}
