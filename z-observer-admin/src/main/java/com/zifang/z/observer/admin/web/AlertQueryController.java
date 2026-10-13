package com.zifang.z.observer.admin.web;

import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.Series;
import com.zifang.z.observer.admin.notify.AlertDispatcher;
import com.zifang.z.observer.admin.rule.Alert;
import com.zifang.z.observer.admin.rule.RuleEngine;
import com.zifang.z.observer.admin.rule.Silence;
import com.zifang.z.observer.admin.config.RuleSpec;
import com.zifang.z.observer.admin.rule.InhibitRule;
import com.zifang.z.observer.admin.rule.Rule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 告警与指标查询的对外接口。 */
@RestController
@RequestMapping("/api")
public class AlertQueryController {

    private final RuleEngine engine;
    private final AlertDispatcher dispatcher;
    private final MetricQuery metricQuery;

    public AlertQueryController(RuleEngine engine, AlertDispatcher dispatcher,
                                MetricQuery metricQuery) {
        this.engine = engine;
        this.dispatcher = dispatcher;
        this.metricQuery = metricQuery;
    }

    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        List<Map<String, Object>> active = new ArrayList<Map<String, Object>>();
        for (Alert a : engine.activeAlerts()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ruleId", a.ruleId());
            m.put("ruleName", a.ruleName());
            m.put("severity", a.severity());
            m.put("state", a.state().name());
            m.put("labels", a.labels());
            m.put("value", a.value());
            m.put("activeSinceMillis", Long.valueOf(a.activeSinceMillis()));
            active.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("active", active);
        out.put("evaluateErrorCount", Long.valueOf(engine.evaluateErrorCount()));
        out.put("lastEvaluateMillis", Long.valueOf(engine.lastEvaluateMillis()));
        out.put("notificationsSent", Long.valueOf(dispatcher.sentCount()));
        out.put("notificationsFailed", Long.valueOf(dispatcher.failedCount()));
        out.put("notificationsSilenced", Long.valueOf(dispatcher.silencedCount()));
        return out;
    }

    /** 已装载的规则。 */
    @GetMapping("/rules")
    public List<Map<String, Object>> rules() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Rule r : engine.rules()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", r.id());
            m.put("name", r.name());
            m.put("severity", r.severity());
            m.put("expression", r.expression().name());
            m.put("promql", r.promql());
            m.put("condition", r.condition() == null ? null : r.condition().name());
            m.put("threshold", Double.valueOf(r.threshold()));
            m.put("forDurationMillis", Long.valueOf(r.forDurationMillis()));
            m.put("groupBy", r.groupBy());
            out.add(m);
        }
        return out;
    }

    /**
     * 运行时注册一条规则。
     *
     * <p>同 id 覆盖。引擎按 ruleId 继承旧告警状态，所以「改阈值」不会让正在确认的
     * 告警从头再来 —— 半夜改配置不至于引发一轮假恢复假告警。
     */
    @PostMapping("/rules")
    public Map<String, Object> addRule(@RequestBody RuleSpec spec) {
        Rule rule = spec.toRule();
        engine.registerRule(rule);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", rule.id());
        out.put("name", rule.name());
        out.put("total", Integer.valueOf(engine.rules().size()));
        return out;
    }

    /** 加一条静默。 */
    @PostMapping("/silences")
    public Map<String, Object> addSilence(@RequestBody SilenceRequest request) {
        Silence silence = new Silence(
                request.id,
                request.matchers == null ? new ArrayList<String>() : request.matchers,
                request.startMillis,
                request.endMillis,
                request.reason,
                request.createdBy);
        engine.addSilence(silence);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", silence.id());
        out.put("activeNow", Boolean.valueOf(silence.activeAt(System.currentTimeMillis())));
        return out;
    }

    /** 加一条抑制规则。 */
    @PostMapping("/inhibits")
    public Map<String, Object> addInhibit(@RequestBody InhibitRequest request) {
        InhibitRule rule = request.equalLabel == null || request.equalLabel.isEmpty()
                ? InhibitRule.any(request.id, request.sourceRuleId, request.targetRuleId)
                : InhibitRule.sameLabelOn(request.id, request.sourceRuleId,
                        request.targetRuleId, request.equalLabel);
        engine.addInhibitRule(rule);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", rule.id());
        out.put("source", rule.sourceRuleId());
        out.put("target", rule.targetRuleId());
        return out;
    }

    /** 即时查询。控制台的指标页直接用它。 */
    @GetMapping("/metrics/query")
    public List<Series> query(@RequestParam("promql") String promql,
                              @RequestParam(value = "time", required = false) Long time) {
        return metricQuery.instant(promql, time == null ? System.currentTimeMillis() : time.longValue());
    }

    /** 区间查询。 */
    @GetMapping("/metrics/query-range")
    public List<Series> queryRange(@RequestParam("promql") String promql,
                                   @RequestParam("from") Long from,
                                   @RequestParam("to") Long to,
                                   @RequestParam(value = "step", required = false,
                                         defaultValue = "60000") Long step) {
        return metricQuery.range(promql, from.longValue(), to.longValue(), step.longValue());
    }

    public static class SilenceRequest {
        public String id;
        public List<String> matchers;
        public long startMillis;
        public long endMillis;
        public String reason;
        public String createdBy;
    }

    public static class InhibitRequest {
        public String id;
        public String sourceRuleId;
        public String targetRuleId;
        public String equalLabel;
    }
}
