package com.zifang.z.observer.admin.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒排索引：词元 -> 文档号升序表。
 *
 * <p>整段日志存内存，检索靠倒排而不是全表扫 —— 全表扫在几万条量级还能忍，
 * 到几十万条、每条几 KB 时一次查询就是秒级，而「刚才那个报错的日志在哪」正是
 * 故障时要抢的那几秒。
 *
 * <p><b>删除靠墓碑。</b> 倒排表里的文档号是有序数组，删一条要从每个命中它的词元表里
 * 挖掉一个元素，代价 O(命中词元数 × 表长)。日志按时间淘汰，删除又最频繁
 * （每写一条就可能淘汰一条），所以这里退一步：表里留墓碑，查询时按存活表过滤。
 * 换来的写入侧 O(1)。
 *
 * <p>线程安全：写入加锁（Netty 的 IO 线程可能多路复用同一个 handler 实例），
 * 查询先取一份表引用快照再在锁外求交。
 */
public final class InvertedIndex {

    /** 精确字段的键前缀，与分词词元物理隔离。 */
    static final String EXACT = String.valueOf((char) 1);

    /** 文档号 -> 是否存活。墓碑即这里没有（或为 false）。 */
    private final Map<Long, Boolean> live = new HashMap<Long, Boolean>();
    private final Map<String, int[]> postings = new HashMap<String, int[]>();

    private long maxDocId = -1;

    public synchronized void add(long docId, List<String> terms) {
        live.put(docId, Boolean.TRUE);
        if (docId > maxDocId) {
            maxDocId = docId;
        }
        for (String term : terms) {
            int[] table = postings.get(term);
            if (table == null) {
                postings.put(term, new int[]{(int) docId});
            } else {
                // 一篇文档里同一个词可能出现多次（"订单订单"）。倒排表只留一个号，
                // 否则 AND 求交时这篇文档会重复命中，命中数虚高。
                int last = table[table.length - 1];
                if (last == (int) docId) {
                    continue;
                }
                int[] grown = new int[table.length + 1];
                System.arraycopy(table, 0, grown, 0, table.length);
                grown[table.length] = (int) docId;
                postings.put(term, grown);
            }
        }
    }

    /** 文档淘汰：打墓碑。表里留着，查询时按存活表过滤。 */
    public synchronized void remove(long docId) {
        live.remove(docId);
    }

    public synchronized boolean isLive(long docId) {
        Boolean v = live.get(docId);
        return v != null && v.booleanValue();
    }

    /**
     * 按词元集检索（词元之间 AND），返回命中的文档号（升序）。
     *
     * <p>先取最短的表当种子再逐个求交。直接拿第一张表当种子的话，
     * 查一个高频词元（中文单字词几乎每条都有）后面每步都在扫大表，
     * 求交从 O(最小表) 退化成 O(最大表)。
     */
    public List<Long> search(List<String> terms) {
        if (terms.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, int[]> snapshot;
        synchronized (this) {
            snapshot = new HashMap<String, int[]>(postings);
        }

        List<int[]> tables = new ArrayList<int[]>(terms.size());
        for (String term : terms) {
            int[] table = snapshot.get(term);
            if (table == null) {
                return Collections.emptyList(); // 有一个词元不存在 ⇒ 交集必空
            }
            tables.add(table);
        }
        Collections.sort(tables, BY_LENGTH);

        int[] current = tables.get(0);
        for (int i = 1; i < tables.size() && current.length > 0; i++) {
            current = intersect(current, tables.get(i));
        }
        List<Long> out = new ArrayList<Long>(current.length);
        for (long id : current) {
            if (isLive(id)) {
                out.add(Long.valueOf(id));
            }
        }
        return out;
    }

    /**
     * 精确取值检索（level / logger / traceId 这类不做分词的字段）。
     *
     * <p>前缀是必需的：否则 {@code level=ERROR} 会去命中 message 里恰好出现 "error" 的日志。
     */
    public List<Long> lookup(String field, String value) {
        return search(Collections.singletonList(EXACT + field + EXACT + value));
    }

    /** 两个有序数组求交。 */
    private static int[] intersect(int[] a, int[] b) {
        int[] out = new int[Math.min(a.length, b.length)];
        int i = 0;
        int j = 0;
        int n = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                out[n++] = a[i];
                i++;
                j++;
            } else if (a[i] < b[j]) {
                i++;
            } else {
                j++;
            }
        }
        if (n == out.length) {
            return out;
        }
        int[] exact = new int[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
    }

    public synchronized int termCount() {
        return postings.size();
    }

    public synchronized long maxDocId() {
        return maxDocId;
    }

    private static final Comparator<int[]> BY_LENGTH = new Comparator<int[]>() {
        @Override
        public int compare(int[] a, int[] b) {
            return a.length - b.length;
        }
    };
}
