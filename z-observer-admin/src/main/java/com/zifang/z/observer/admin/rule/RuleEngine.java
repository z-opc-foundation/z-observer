package com.zifang.z.observer.admin.rule;

import com.zifang.z.observer.admin.metric.MetricQuery;
import com.zifang.z.observer.admin.metric.PrometheusQueryException;
import com.zifang.z.observer.admin.metric.Series;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
 *
 * <p><b>两段确认有两把闸门，缺一不可。</b>
 * <ul>
 *   <li><b>时长闸门</b>（{@code forDurationMillis}）：条件要连续成立这么久。
 *       连续性靠「条件断一轮 ⇒ 待确认作废重来」保证。</li>
 *   <li><b>计数闸门</b>（{@code forHits}）：条件要连续命中 N 个评估周期。
 *       命中-未命中交替时连续命中数永远攒不到 N ⇒ 永远不 firing ——
 *       这正是「抖动不告警」真正生效的地方，<b>不是</b>把阈值调大凑出来的。</li>
 * </ul>
 * 两把闸门是<b>与</b>关系：都满足才进 FIRING。计数状态落在 {@link Alert#hitCount()}
 * （确认次数），每轮命中 +1，条件断一轮随记录一起作废归零。
 *
 * <p><b>状态变迁全程留痕</b>（{@link AlertTransition}）：每一格状态变化都记一条，
 * 含当时的确认次数 ⇒ 事后能完整回放「这条告警为什么在第几轮才发出去」。
 * 抖动误报和真故障在最终状态上长得一模一样，<b>过程</b>才是判断依据。
 */
public final class RuleEngine {

    private static final Logger LOG = LogManager.getLogger(RuleEngine.class);

    /** 状态轨迹留条数上限（内存里滚动保留最近的）。 */
    private static final int MAX_TRANSITIONS = 200;

    private final MetricQuery query;
    private final AlertNotifier notifier;

    private final Map<String, Rule> rules = new LinkedHashMap<String, Rule>();
    private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<String, Alert>();
    private final List<InhibitRule> inhibitRules =
            Collections.synchronizedList(new ArrayList<InhibitRule>());
    private final List<Silence> silences =
            Collections.synchronizedList(new ArrayList<Silence>());
    private final List<AlertTransition> transitions =
            Collections.synchronizedList(new ArrayList<AlertTransition>());

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

    /**
     * 状态变迁轨迹（最近若干条，新的在后）。
     *
     * <p>它是「判定链」的读数：一条告警从进 PENDING 到进 FIRING 中间发生了什么，
     * 每一步都在这里，能按 {@link AlertTransition#fingerprint()} 串成一条链。
     */
    public List<AlertTransition> transitions() {
        synchronized (transitions) {
            return new ArrayList<AlertTransition>(transitions);
        }
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

        // 同一 group_by 组里的多条时序聚合成一条告警 ⇒ 一轮内只记一次命中。
        // 不去重的话，组里有 3 条时序时确认计数一轮就 +3，「连续命中 N 个周期」
        // 会被同一轮里的兄弟时序灌满 —— 计数闸门就名存实亡了。
        Map<String, Hit> byFingerprint = new LinkedHashMap<String, Hit>();
        for (Hit hit : hits) {
            String fingerprint = fingerprintOf(rule, hit.labels);
            if (!byFingerprint.containsKey(fingerprint)) {
                byFingerprint.put(fingerprint, hit);
            }
        }

        int sent = 0;
        for (Map.Entry<String, Hit> e : byFingerprint.entrySet()) {
            sent += advance(rule, e.getValue(), e.getKey(), nowMillis);
        }
        // 本轮没命中的旧实例：收尾并发恢复通知。
        // 不做这一步的话条件恢复时永远走不到 RESOLVED，恢复通知就发不出来。
        for (Map.Entry<String, Alert> e : new ArrayList<Map.Entry<String, Alert>>(
                activeAlerts.entrySet())) {
            if (!e.getKey().startsWith(rule.id() + "|") || byFingerprint.containsKey(e.getKey())) {
                continue;
            }
            Alert previous = e.getValue();
            activeAlerts.remove(e.getKey());
            if (previous.state() == AlertState.FIRING) {
                transition(rule, e.getKey(), AlertState.FIRING, AlertState.RESOLVED, nowMillis,
                        previous.hitCount(), "条件不再成立，告警恢复，发恢复通知");
                notifier.notifyResolved(previous.withState(AlertState.RESOLVED, nowMillis));
                sent++;
            } else if (previous.state() == AlertState.PENDING) {
                // 中途回 OK：待确认作废。确认计数随之清零 ——
                // 不清零的话下一轮重新命中会接着旧的数攒，「连续命中」就不是连续了。
                transition(rule, e.getKey(), AlertState.PENDING, AlertState.OK, nowMillis, 0,
                        "待确认作废（作废前确认计数 " + previous.hitCount() + "），条件断一轮，确认计数清零重来");
            }
        }
        return sent;
    }

    private List<Hit> evaluateSimple(Rule rule, long nowMillis) {
        if (rule.expression() == Expression.ABSENT) {
            return evaluateAbsent(rule, nowMillis);
        }
        List<Hit> hits = new ArrayList<Hit>();
        for (Series s : query.instant(rule.promql(), nowMillis)) {
            if (rule.condition().test(s.value(), rule.threshold())) {
                hits.add(new Hit(s.labels(), String.valueOf(s.value())));
            }
        }
        return hits;
    }

    /**
     * absent：既看「查询有没有结果」，也看「结果有多旧」。
     *
     * <p>只看结果为空不够：Prometheus 的 instant 查询默认回看 5 分钟，进程刚挂时
     * 它仍返回最后一个点（up=0 也在），要等满 5 分钟 staleness 才判消失 ——
     * M1 的 60 秒口径当场不成立。所以多看一眼 lastSeen，超过宽限就算消失。
     *
     * <p>反过来只看 lastSeen 也不够：lastSeen 为 -1 意味着这条时序<b>从来没被采到过</b>，
     * 那多半是表达式写错或 job 名不对，不是某个进程挂了。这两种「没数据」必须分开，
     * 否则值班的人会为一个拼错的 job 名折腾半天。
     */
    private List<Hit> evaluateAbsent(Rule rule, long nowMillis) {
        List<Series> series = query.instant(rule.promql(), nowMillis);
        long lastSeen = query.lastSeenMillis(rule.promql());
        if (!series.isEmpty()
                && (lastSeen < 0 || nowMillis - lastSeen <= rule.absentGraceMillis())) {
            return Collections.emptyList();
        }
        // lastSeenMillis 绝不能进 labels：labels 参与 fingerprint，而它每轮都在变。
        // 那样每轮都算「一条新告警」，PENDING 永远攒不满确认时长，告警一次都发不出去。
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("ruleId", rule.id());
        labels.put("promql", rule.promql());
        Map<String, String> annotations = new LinkedHashMap<String, String>();
        annotations.put("lastSeenMillis", String.valueOf(lastSeen));
        annotations.put("staleSeconds",
                String.valueOf((nowMillis - Math.max(lastSeen, 0L)) / 1000L));
        return Collections.singletonList(new Hit(labels, String.valueOf(lastSeen), annotations));
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
            // 首次命中：进 PENDING，确认计数 = 1。
            Alert created = new Alert(rule.id(), rule.name(), rule.severity(),
                    hit.labels, hit.annotations,
                    AlertState.PENDING, nowMillis, nowMillis, hit.value, 1);
            activeAlerts.put(fingerprint, created);
            transition(rule, fingerprint, AlertState.OK, AlertState.PENDING, nowMillis, 1,
                    "首次命中，进入待确认（确认计数 1/" + rule.forHits() + "）");
            return tryFire(rule, created, fingerprint, nowMillis);
        }
        if (previous.state() == AlertState.FIRING) {
            activeAlerts.put(fingerprint, previous.withState(AlertState.FIRING, nowMillis));
            return 0;
        }
        // PENDING：本轮又命中 ⇒ 确认计数 +1（连续命中；断一轮就作废重来，见 evaluateRule）
        int hits = previous.hitCount() + 1;
        Alert pending = previous.withHitCount(hits, nowMillis);
        activeAlerts.put(fingerprint, pending);
        int sent = tryFire(rule, pending, fingerprint, nowMillis);
        if (sent == 0) {
            transition(rule, fingerprint, AlertState.PENDING, AlertState.PENDING, nowMillis, hits,
                    "命中但未确认（确认计数 " + hits + "/" + rule.forHits()
                            + "，已持续 " + (nowMillis - pending.activeSinceMillis())
                            + "ms/" + rule.forDurationMillis() + "ms）");
        }
        return sent;
    }

    /**
     * 两把闸门都过了才放行。
     *
     * @return 是否放行（放行即发通知）
     */
    private int tryFire(Rule rule, Alert pending, String fingerprint, long nowMillis) {
        int hits = pending.hitCount();
        long elapsed = nowMillis - pending.activeSinceMillis();
        boolean durationOk = elapsed >= rule.forDurationMillis();
        boolean countOk = hits >= rule.forHits();
        if (!durationOk || !countOk) {
            return 0;
        }
        Alert firing = pending.withState(AlertState.FIRING, nowMillis);
        activeAlerts.put(fingerprint, firing);
        transition(rule, fingerprint, AlertState.PENDING, AlertState.FIRING, nowMillis, hits,
                "两段确认通过（连续命中 " + hits + " 次 ≥ " + rule.forHits()
                        + "，持续 " + elapsed + "ms ≥ " + rule.forDurationMillis() + "ms）");
        dispatch(firing, nowMillis);
        return 1;
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

    /** 记一条状态变迁，并打一条日志（状态变了才打 INFO，待确认内的计数推进打 DEBUG）。 */
    private void transition(Rule rule, String fingerprint, AlertState from, AlertState to,
                            long nowMillis, int hitCount, String reason) {
        AlertTransition t = new AlertTransition(rule.id(), fingerprint, from, to,
                nowMillis, hitCount, reason);
        synchronized (transitions) {
            transitions.add(t);
            while (transitions.size() > MAX_TRANSITIONS) {
                transitions.remove(0);
            }
        }
        if (from == to) {
            LOG.debug("告警状态 {} {} -> {} 确认计数={} {}", rule.id(), from, to,
                    Integer.valueOf(hitCount), reason);
        } else {
            LOG.info("告警状态 {} {} -> {} 确认计数={} {}", rule.id(), from, to,
                    Integer.valueOf(hitCount), reason);
        }
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
        private final Map<String, String> annotations;

        Hit(Map<String, String> labels, String value) {
            this(labels, value, Collections.<String, String>emptyMap());
        }

        Hit(Map<String, String> labels, String value, Map<String, String> annotations) {
            this.labels = labels;
            this.value = value;
            this.annotations = annotations;
        }
    }
}
