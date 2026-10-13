package com.zifang.z.observer.admin.web;

import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.PrometheusQueryException;
import com.zifang.z.observer.admin.metric.Series;
import com.zifang.z.observer.admin.notify.AlertDispatcher;
import com.zifang.z.observer.admin.notify.LoggingChannel;
import com.zifang.z.observer.admin.rule.RuleEngine;
import com.zifang.z.observer.admin.store.LogStore;
import com.zifang.z.observer.admin.store.TraceStore;
import com.zifang.z.observer.core.model.LogEvent;
import com.zifang.z.observer.core.model.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 查询面走真的 MockMvc + 真的存储，只把 Prometheus 换成一个假的查询面。
 *
 * <p>要抓的是「接口层自己才会犯的错」：参数没接线、前缀写错、状态码选错 ——
 * 这些在存储层单测里永远看不见。
 */
class WebQueryApiTest {

    private LogStore logStore;
    private TraceStore traceStore;
    private MockMvc mvc;
    private StubMetricQuery metricQuery;

    @BeforeEach
    void setUp() {
        logStore = new LogStore();
        traceStore = new TraceStore();
        metricQuery = new StubMetricQuery();
        Map<String, List<com.zifang.z.observer.admin.notify.MessageChannel>> ch =
                new LinkedHashMap<String, List<com.zifang.z.observer.admin.notify.MessageChannel>>();
        ch.put("*", new ArrayList<com.zifang.z.observer.admin.notify.MessageChannel>(
                Arrays.asList(new LoggingChannel())));
        RuleEngine engine = new RuleEngine(metricQuery, new AlertDispatcher(ch));

        mvc = MockMvcBuilders.standaloneSetup(
                        new LogQueryController(logStore),
                        new TraceController(traceStore),
                        new AlertQueryController(engine, new AlertDispatcher(ch), metricQuery))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static LogEvent log(long ts, String level, String message, String traceId) {
        LogEvent.Builder b = LogEvent.builder()
                .timestampMillis(ts).level(level)
                .logger("com.demo.Foo").thread("main").message(message);
        if (traceId != null) {
            b.traceId(traceId);
        }
        return b.build();
    }

    @Test
    void 日志检索走api前缀且能按中文关键词命中() throws Exception {
        logStore.append(log(1000, "INFO", "订单创建成功", null));
        logStore.append(log(1001, "ERROR", "订单创建失败: 数据库连接超时", "t1"));

        mvc.perform(get("/api/logs").param("q", "失败"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("订单创建失败: 数据库连接超时"))
                .andExpect(jsonPath("$[0].traceId").value("t1"));
    }

    @Test
    void 日志按级别与traceId过滤() throws Exception {
        logStore.append(log(1000, "INFO", "a", null));
        logStore.append(log(1001, "ERROR", "b", "t1"));
        logStore.append(log(1002, "ERROR", "c", "t2"));

        mvc.perform(get("/api/logs").param("level", "ERROR"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/logs").param("traceId", "t2"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].message").value("c"));
    }

    @Test
    void 日志按field等值过滤() throws Exception {
        logStore.append(LogEvent.builder()
                .timestampMillis(1000).level("INFO").logger("L").thread("main")
                .message("x")
                .fields(single("host", "node-a")).build());
        logStore.append(LogEvent.builder()
                .timestampMillis(1001).level("INFO").logger("L").thread("main")
                .message("y")
                .fields(single("host", "node-b")).build());

        mvc.perform(get("/api/logs").param("field", "host=node-b"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].message").value("y"));
    }

    @Test
    void field格式不对返400而不是500() throws Exception {
        mvc.perform(get("/api/logs").param("field", "host"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void 日志limit真的生效() throws Exception {
        for (int i = 0; i < 20; i++) {
            logStore.append(log(1000 + i, "INFO", "line " + i, null));
        }
        mvc.perform(get("/api/logs").param("limit", "5"))
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void 存储健康度可查() throws Exception {
        logStore.append(log(1000, "INFO", "a", null));
        mvc.perform(get("/api/logs/status"))
                .andExpect(jsonPath("$.liveLogs").value(1))
                .andExpect(jsonPath("$.evictedLogs").value(0));
    }

    @Test
    void 链路树按父子层级返回() throws Exception {
        traceStore.append(SpanData.builder().traceId("t1").spanId("s1")
                .name("GET /order").startTimeMillis(1000).endTimeMillis(1500).build());
        traceStore.append(SpanData.builder().traceId("t1").spanId("s2").parentSpanId("s1")
                .name("db.query").startTimeMillis(1100).endTimeMillis(1200).build());

        mvc.perform(get("/api/traces/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spanCount").value(2))
                .andExpect(jsonPath("$.roots[0].name").value("GET /order"))
                .andExpect(jsonPath("$.roots[0].children[0].name").value("db.query"))
                .andExpect(jsonPath("$.roots[0].children[0].children.length()").value(0));
    }

    @Test
    void 链路不存在返404_不是空树() throws Exception {
        mvc.perform(get("/api/traces/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("nope")));
    }

    @Test
    void 最近链路列表与计数() throws Exception {
        traceStore.append(SpanData.builder().traceId("t1").spanId("s1")
                .name("a").startTimeMillis(1000).endTimeMillis(1100).build());
        traceStore.append(SpanData.builder().traceId("t2").spanId("s9")
                .name("b").startTimeMillis(1000).endTimeMillis(1100).build());

        mvc.perform(get("/api/traces").param("limit", "1"))
                .andExpect(jsonPath("$.traceCount").value(2))
                .andExpect(jsonPath("$.traceIds.length()").value(1));
    }

    @Test
    void 加静默后接口回显id且立即生效() throws Exception {
        String body = "{\"id\":\"s1\",\"matchers\":[\"cluster=c1\"],"
                + "\"startMillis\":0,\"endMillis\":9223372036854775807,"
                + "\"reason\":\"发布窗口\",\"createdBy\":\"ceo\"}";

        mvc.perform(post("/api/silences").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("s1"))
                .andExpect(jsonPath("$.activeNow").value(true));
    }

    @Test
    void 抑制规则接口带上相等标签() throws Exception {
        mvc.perform(post("/api/inhibits").contentType("application/json")
                        .content("{\"id\":\"i1\",\"sourceRuleId\":\"r1\","
                                + "\"targetRuleId\":\"r2\",\"equalLabel\":\"cluster\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("i1"))
                .andExpect(jsonPath("$.source").value("r1"))
                .andExpect(jsonPath("$.target").value("r2"));
    }

    @Test
    void 告警列表在无活动告警时也要给出完整计数结构() throws Exception {
        mvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.length()").value(0))
                .andExpect(jsonPath("$.evaluateErrorCount").value(0))
                .andExpect(jsonPath("$.notificationsSent").value(0));
    }

    @Test
    void 指标即时查询把promql透传下去() throws Exception {
        mvc.perform(get("/api/metrics/query").param("promql", "up").param("time", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value(1.0));
        assertThat(metricQuery.lastPromql).isEqualTo("up");
    }

    @Test
    void 指标区间查询带上起止与步长() throws Exception {
        mvc.perform(get("/api/metrics/query-range").param("promql", "up")
                        .param("from", "1000").param("to", "5000").param("step", "1000"))
                .andExpect(status().isOk());
        assertThat(metricQuery.lastRange).isEqualTo("1000|5000|1000");
    }

    @Test
    void 指标源不可达返502而不是500() throws Exception {
        metricQuery.fail = true;
        mvc.perform(get("/api/metrics/query").param("promql", "up"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("指标源不可达")));
    }

    private static Map<String, String> single(String k, String v) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }

    /** 只固定住「promql 真的传下去了」这一件事，指标解析本身另有 PrometheusHttpClient 的测试。 */
    private static final class StubMetricQuery implements MetricQuery {
        private String lastPromql;
        private String lastRange;
        private boolean fail;

        @Override
        public List<Series> instant(String promql, long timeMillis) {
            if (fail) {
                throw new PrometheusQueryException("connection refused");
            }
            lastPromql = promql;
            Map<String, String> labels = new LinkedHashMap<String, String>();
            labels.put("app", "z-gw");
            return Collections.singletonList(new Series(labels, 1.0d, timeMillis));
        }

        @Override
        public List<Series> range(String promql, long from, long to, long step) {
            lastRange = from + "|" + to + "|" + step;
            return Collections.emptyList();
        }

        @Override
        public long lastSeenMillis(String promql) {
            return -1L;
        }
    }
}