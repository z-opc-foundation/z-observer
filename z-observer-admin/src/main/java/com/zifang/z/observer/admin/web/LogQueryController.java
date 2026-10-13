package com.zifang.z.observer.admin.web;

import com.zifang.z.observer.admin.store.LogQuery;
import com.zifang.z.observer.admin.store.LogStore;
import com.zifang.z.observer.core.model.LogEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 日志检索。
 *
 * <p>所有 API 都在 {@code /api/} 前缀下 —— 与 SPA 路由彻底隔开。
 * 用 {@code /logs} 这种裸路径的话，前端路由到 {@code /logs} 刷新时拿到的
 * 是这段 JSON 而不是页面。
 */
@RestController
@RequestMapping("/api/logs")
public class LogQueryController {

    private final LogStore logStore;

    public LogQueryController(LogStore logStore) {
        this.logStore = logStore;
    }

    @GetMapping
    public List<LogEvent> search(@RequestParam(value = "q", required = false) String text,
                                 @RequestParam(value = "level", required = false) String level,
                                 @RequestParam(value = "logger", required = false) String logger,
                                 @RequestParam(value = "traceId", required = false) String traceId,
                                 @RequestParam(value = "field", required = false) String field,
                                 @RequestParam(value = "from", required = false) Long from,
                                 @RequestParam(value = "to", required = false) Long to,
                                 @RequestParam(value = "limit", required = false,
                                                 defaultValue = "100") int limit) {
        LogQuery.Builder builder = LogQuery.builder()
                .text(text).level(level).logger(logger).traceId(traceId)
                .fromMillis(from == null ? 0 : from.longValue())
                .toMillis(to == null ? 0 : to.longValue())
                .limit(limit);
        if (field != null && !field.isEmpty()) {
            int eq = field.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("field 参数格式应为 k=v: " + field);
            }
            builder.field(field.substring(0, eq), field.substring(eq + 1));
        }
        return logStore.search(builder.build());
    }

    /** 存储自身的健康度：淘汰了多少条。查不到旧日志时先看这里。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("liveLogs", Integer.valueOf(logStore.size()));
        m.put("evictedLogs", Long.valueOf(logStore.evictedCount()));
        return m;
    }
}
