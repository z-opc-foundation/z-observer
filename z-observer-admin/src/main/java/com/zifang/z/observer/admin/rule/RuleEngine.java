package com.zifang.z.observer.admin.rule;

import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.PrometheusQueryException;
import com.zifang.z.observer.admin.metric.Series;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则引擎：评估 + 两段确认 + 去噪 + 通知。
 *
 * <p><b>一次评估的完整链路</b>：查指标 → 算出每个告警实例「本轮是否成立」
 * → 和上一轮的状态机比对，决定要不要发通知 → 过静默和抑制 → 发。
 *
 * <p><b>本轮不再成立的实例必须显式收尾。</b> 只处理「成立」的告警看起来更简洁，
 * 但那样一来条件恢复时永远走不到 RESOLVED，恢复通知就发不出来 ——
 * 而「恢复通知跟随」是需求里明写的验收项。
 *
 * <p><b>查询失败不改变状态。</b> 采集面抖一下不该让所有告警「恢复」。
 * PrometheusQueryException 单独接住、计入错误数、状态原样留着，
 * 下一轮条件真不成立时才走恢复。
 */
public final class RuleEngine {

    private final MetricQuery query;
    private final AlertNotifier notifier;

    private final Map<String, Rule> rules = new LinkedHashMap<String, Rule>();
    private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<String, Alert>();
    private final List<InhibitRule> inhibitRules =
            Collections.synchronizedList(new ArrayList<InhibitRule>());
    private final List<Silence> silences =
            Collections.synchronizedList(new ArrayList<Silence>());

    private volatile long evaluateErrorCount;
    private volatile long lastEvaluateMillis;

    public RuleEngine(MetricQuery query, AlertNotifier notifier) {
        if (query == null || notifier == null) {
            throw new IllegalArgumentException("查询面与通知出口必填");
        }
        this.query = query;
        this.notifier = notifier;
    }

    public void registerRule(Rule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule 必填");
        }
        synchronized (rules) {
            rules.put(rule.id(), rule);
        }
    }

    public void addInhibitRule(InhibitRule rule) {
        inhibitRules.add(rule);
    }

    public void addSilence(Silence silence) {
        silences.add(silence);
    }

    public List<Rule> rules() {
        synchronized (rules) {
            return new ArrayList<Rule>(rules.values());
        }
    }

    /** 当前所有活跃告警（含 PENDING）。 */
    public List<Alert> activeAlerts() {
        return new ArrayList<Alert>(activeAlerts.values());
    }

    public long evaluateErrorCount() {
        return evaluateErrorCount;
    }

    /**
     * 跑一轮评估。
     *
     * @return 本轮发出的通知条数
     */
    public int evaluate(long nowMillis) {
        lastEvaluateMillis = nowMillis;
        int notifications = 0;
        List<Rule> snapshot;
        synchronized (rules) {
            snapshot = new ArrayList<Rule>(rules.values());
        }
        for (Rule rule : snapshot) {
            try {
                notifications += evaluateRule(rule, nowMillis);
            } catch (PrometheusQueryException e) {
                // 采集面坏了，不是业务事实。状态一个字都不动。
                evaluateErrorCount++;
            }
        }
        return notifications;
    }

    private int evaluateRule(Rule rule, long nowMillis) {
        List<Hit> hits;
        if (rule.expression() == Expression.BOOLEAN) {
            hits = evaluateBoolean(rule, nowMillis);
        } else {
            hits = evaluateSimple(rule, nowMillis);
        }

        java.util.Set<String> stillActive = new java.util.HashSet<String>();
        int sent = 0;
        for (Hit hit : hits) {
            String fingerprint = fingerprintOf(rule, hit.labels);
            stillActive.add(fingerprint);
            sent += advance(rule, hit, fingerprint, nowMillis);
        }
        // 本轮没命中的旧实例：收尾并发恢复通知。
        // 不做这一步的话条件恢复时永远走不到 RESOLVED，恢复通知就发不出来。
        for (Map.Entry<String, Alert> e : new ArrayList<Map.Entry<String, Alert>>(
                activeAlerts.entrySet())) {
            if (!e.getKey().startsWith(rule.id() + "|") || stillActive.contains(e.getKey())) {
                continue;
            }
            Alert previous = e.getValue();
            activeAlerts.remove(e.getKey());
            if (previous.state() == AlertState.FIRING) {
                notifier.notifyResolved(previous.withState(AlertState.RESOLVED, nowMillis));
                sent++;
            }
        }
        return sent;
    }

    private List<Hit> evaluateSimple(Rule rule, long nowMillis) {
        List<Series> series = query.instant(rule.promql(), nowMillis);
        List<Hit> hits = new ArrayList<Hit>();
        if (rule.expression() == Expression.ABSENT) {
            // absent 的语义是「查不到」，不是「查到 0」。进程彻底消失后 Prometheus
            // 会因 staleness 把时序从结果里删掉，所以 `up == 0` 恰恰在最需要它时失效。
            if (series.isEmpty()) {
                hits.add(new Hit(new LinkedHashMap<String, String>(), null));
            }
            return hits;
        }
        for (Series s : series) {
            if (rule.condition().test(s.value(), rule.threshold())) {
                hits.add(new Hit(s.labels(), String.valueOf(s.value())));
            }
        }
        return hits;
    }

    /**
     * 组合布尔：所有子条件按<b>同一组标签</b>同时成立才算命中。
     *
     * <p>等价于 PromQL 的 {@code and}。「错误率高」单独看没有意义 ——
     * 半夜没流量时错误率恒为 1，单独告警会一直响；加上「请求量 &gt; 0」之后
     * 才是「真有请求在失败」。
     */
    private List<Hit> evaluateBoolean(Rule rule, long nowMillis) {
        Map<String, Hit> intersection = null;
        for (Rule.SubCondition sub : rule.conditions()) {
            Map<String, Hit> current = new LinkedHashMap<String, Hit>();
            for (Series s : query.instant(sub.promql(), nowMillis)) {
                if (sub.condition().test(s.value(), sub.threshold())) {
                    current.put(labelSignature(s.labels()),
                            new Hit(s.labels(), String.valueOf(s.value())));
                }
            }
            if (intersection == null) {
                intersection = current;
            } else {
                java.util.Iterator<String> it = intersection.keySet().iterator();
                while (it.hasNext()) {
                    if (!current.containsKey(it.next())) {
                        it.remove();
                    }
                }
            }
        }
        return intersection == null
                ? Collections.<Hit>emptyList() : new ArrayList<Hit>(intersection.values());
    }

    private static String labelSignature(Map<String, String> labels) {
        java.util.List<String> names = new ArrayList<String>(labels.keySet());
        Collections.sort(names);
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            sb.append(n).append('=').append(labels.get(n)).append(',');
        }
        return sb.toString();
    }

    /** 状态机推进一格。返回本步是否发了通知。 */
    private int advance(Rule rule, Hit hit, String fingerprint, long nowMillis) {
        Alert previous = activeAlerts.get(fingerprint);
        if (previous == null) {
            Alert created = new Alert(rule.id(), rule.name(), rule.severity(),
                    hit.labels, Collections.<String, String>emptyMap(),
                    AlertState.PENDING, nowMillis, nowMillis, hit.value);
            activeAlerts.put(fingerprint, created);
            if (rule.forDurationMillis() == 0) {
                Alert firing = created.withState(AlertState.FIRING, nowMillis);
                activeAlerts.put(fingerprint, firing);
                dispatch(firing, nowMillis);
                return 1;
            }
            return 0;
        }
        if (previous.state() == AlertState.FIRING) {
            activeAlerts.put(fingerprint, previous.withState(previous.state(), nowMillis));
            return 0;
        }
        // PENDING -> FIRING：条件连续成立够久了吗
        if (nowMillis - previous.activeSinceMillis() >= rule.forDurationMillis()) {
            Alert firing = previous.withState(AlertState.FIRING, nowMillis);
            activeAlerts.put(fingerprint, firing);
            dispatch(firing, nowMillis);
            return 1;
        }
        activeAlerts.put(fingerprint, previous.withState(AlertState.PENDING, nowMillis));
        return 0;
    }

    /** 过静默与抑制后发通知。被压住的不发，但状态仍然是 FIRING。 */
    private void dispatch(Alert alert, long nowMillis) {
        List<InhibitRule> inhibits;
        synchronized (inhibitRules) {
            inhibits = new ArrayList<InhibitRule>(inhibitRules);
        }
        for (InhibitRule inhibit : inhibits) {
            if (!inhibit.targetRuleId().equals(alert.ruleId())) {
                continue;
            }
            if (inhibit.suppresses(alert, activeAlerts())) {
                return;
            }
        }
        Silence silence;
        synchronized (silences) {
            silence = Silence.firstMatch(new ArrayList<Silence>(silences), alert, nowMillis);
        }
        notifier.notifyFiring(alert, silence);
    }

    /** Alert.fingerprint 本身就以 ruleId 打头，stateMap 的键不需要再拼一次。 */
    private static String fingerprintOf(Rule rule, Map<String, String> labels) {
        return new Alert(rule.id(), rule.name(), rule.severity(),
                labels, Collections.<String, String>emptyMap(),
                AlertState.OK, 0, 0, null).fingerprint(rule.groupBy());
    }

    public long lastEvaluateMillis() {
        return lastEvaluateMillis;
    }

    private static final class Hit {
        private final Map<String, String> labels;
        private final String value;

        Hit(Map<String, String> labels, String value) {
            this.labels = labels;
            this.value = value;
        }
    }
}
