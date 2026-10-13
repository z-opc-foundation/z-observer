package com.zifang.z.observer.admin.web;

import com.zifang.z.observer.admin.store.TraceStore;
import com.zifang.z.observer.core.model.SpanData;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 链路查询：按 traceId 取树，按时间窗列最近链路。 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceStore traceStore;

    public TraceController(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    /**
     * 一条链路的树。
     *
     * <p>不存在返回 404 而不是空树：控制台要能区分「这条链路没采到」
     * 和「采到了但是空的」，前者通常意味着 SDK 没接上。
     */
    @GetMapping("/{traceId}")
    public Map<String, Object> trace(@PathVariable("traceId") String traceId) {
        TraceStore.TraceTree tree = traceStore.trace(traceId);
        if (tree == null) {
            throw new TraceNotFoundException(traceId);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("traceId", tree.traceId());
        out.put("spanCount", Integer.valueOf(tree.spanCount()));
        out.put("orphanCount", Integer.valueOf(tree.orphanCount()));
        out.put("roots", renderNodes(tree.roots()));
        return out;
    }

    private static Object renderNodes(List<TraceStore.TraceNode> nodes) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<Map<String, Object>>();
        for (TraceStore.TraceNode n : nodes) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            SpanData s = n.span();
            m.put("spanId", s.spanId());
            m.put("parentSpanId", s.parentSpanId());
            m.put("name", s.name());
            m.put("kind", s.kind() == null ? null : s.kind().name());
            m.put("startTimeMillis", Long.valueOf(s.startTimeMillis()));
            m.put("durationMillis", Long.valueOf(s.durationMillis()));
            m.put("statusCode", s.statusCode());
            m.put("attributes", s.attributes());
            m.put("children", renderNodes(n.children()));
            out.add(m);
        }
        return out;
    }

    @GetMapping
    public Map<String, Object> recent(@RequestParam(value = "limit", required = false,
            defaultValue = "20") int limit) {
        List<String> ids = traceStore.recentTraceIds(limit);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("traceIds", ids);
        out.put("traceCount", Integer.valueOf(traceStore.traceCount()));
        out.put("evictedTraces", Long.valueOf(traceStore.evictedCount()));
        out.put("truncatedSpans", Long.valueOf(traceStore.truncatedCount()));
        return out;
    }
}
