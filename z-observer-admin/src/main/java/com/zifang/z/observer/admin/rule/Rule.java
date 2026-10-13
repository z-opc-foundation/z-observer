package com.zifang.z.observer.admin.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条告警规则。
 *
 * <p>三类表达式，对应三种「什么时候该叫人」：
 * <ul>
 *   <li>{@link Expression#THRESHOLD} —— 有数值，超了线（错误率 &gt; 1%）</li>
 *   <li>{@link Expression#ABSENT} —— 没数值，压根没数据（进程挂了）</li>
 *   <li>{@link Expression#BOOLEAN} —— 多个条件组合（磁盘满 <b>且</b> 超过 10 分钟）</li>
 * </ul>
 *
 * <p>{@code forDurationMillis} 就是两段确认的「段」：条件要连续成立这么久才进 FIRING。
 *
 * <p><b>规则是不可变对象。</b> 运行中改规则会丢掉状态机里已有的 PENDING 进度，
 * 改一次规则就等于让所有正在确认的告警重新从头来 —— 半夜改配置时这会造成一轮假恢复假告警。
 * 改规则走「换一条新规则对象」，引擎按 ruleId 继承旧状态。
 */
public final class Rule {

    private final String id;
    private final String name;
    private final String severity;
    private final Expression expression;
    private final String promql;
    private final Condition condition;
    private final double threshold;
    private final long forDurationMillis;
    private final List<String> groupBy;
    private final String summary;
    private final String description;
    private final List<SubCondition> conditions;
    private final long absentGraceMillis;

    private Rule(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.severity = b.severity;
        this.expression = b.expression;
        this.promql = b.promql;
        this.condition = b.condition;
        this.threshold = b.threshold;
        this.forDurationMillis = b.forDurationMillis;
        this.groupBy = Collections.unmodifiableList(new ArrayList<String>(b.groupBy));
        this.summary = b.summary;
        this.description = b.description;
        this.conditions = Collections.unmodifiableList(new ArrayList<SubCondition>(b.conditions));
        this.absentGraceMillis = b.absentGraceMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String severity() {
        return severity;
    }

    public Expression expression() {
        return expression;
    }

    public String promql() {
        return promql;
    }

    public Condition condition() {
        return condition;
    }

    public double threshold() {
        return threshold;
    }

    public long forDurationMillis() {
        return forDurationMillis;
    }

    public List<String> groupBy() {
        return groupBy;
    }

    /** 组合布尔的子条件。THRESHOLD/ABSENT 用不到。 */
    public List<SubCondition> conditions() {
        return conditions;
    }

    /**
     * absent 判定里「多久没采到才算消失」的宽限。
     *
     * <p>必须大于 Prometheus 的 instant 查询回看窗口（默认 5 分钟）才有意义 ——
     * 进程刚挂时 instant 查询仍会返回它最后一个点，只看「结果为空」会等满 5 分钟
     * 才判消失，M1 的 60 秒口径直接不成立。
     */
    public long absentGraceMillis() {
        return absentGraceMillis;
    }

    /** 一条布尔规则里的一项。语义等价于 PromQL 的 `and`：按标签集合求交集。 */
    public static final class SubCondition {
        private final String promql;
        private final Condition condition;
        private final double threshold;

        public SubCondition(String promql, Condition condition, double threshold) {
            if (promql == null || promql.trim().isEmpty()) {
                throw new IllegalArgumentException("子条件 promql 必填");
            }
            if (condition == null) {
                throw new IllegalArgumentException("子条件缺比较算子: " + promql);
            }
            this.promql = promql;
            this.condition = condition;
            this.threshold = threshold;
        }

        public String promql() {
            return promql;
        }

        public Condition condition() {
            return condition;
        }

        public double threshold() {
            return threshold;
        }
    }

    public String summary() {
        return summary;
    }

    public String description() {
        return description;
    }

    public static final class Builder {
        private String id;
        private String name;
        private String severity = "warning";
        private Expression expression = Expression.THRESHOLD;
        private String promql;
        private Condition condition = Condition.GT;
        private double threshold;
        private long forDurationMillis = 30_000L;
        private final List<String> groupBy = new ArrayList<String>();
        private final List<SubCondition> conditions = new ArrayList<SubCondition>();
        private String summary;
        private String description;
        private long absentGraceMillis = 30_000L;

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder severity(String v) {
            this.severity = v;
            return this;
        }

        public Builder expression(Expression v) {
            this.expression = v;
            return this;
        }

        public Builder promql(String v) {
            this.promql = v;
            return this;
        }

        public Builder condition(Condition v) {
            this.condition = v;
            return this;
        }

        public Builder threshold(double v) {
            this.threshold = v;
            return this;
        }

        public Builder forDurationMillis(long v) {
            if (v < 0) {
                throw new IllegalArgumentException("确认时长不能为负: " + v);
            }
            this.forDurationMillis = v;
            return this;
        }

        public Builder groupBy(String... labels) {
            Collections.addAll(this.groupBy, labels);
            return this;
        }

        public Builder summary(String v) {
            this.summary = v;
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder absentGraceMillis(long v) {
            if (v < 0) {
                throw new IllegalArgumentException("消失宽限不能为负: " + v);
            }
            this.absentGraceMillis = v;
            return this;
        }

        public Builder condition(SubCondition v) {
            this.conditions.add(v);
            return this;
        }

        public Rule build() {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("规则 id 必填");
            }
            if (expression == Expression.BOOLEAN) {
                if (conditions.size() < 2) {
                    throw new IllegalArgumentException(
                            "组合布尔规则 " + id + " 至少要两个子条件，收到 " + conditions.size());
                }
            } else if (promql == null || promql.trim().isEmpty()) {
                throw new IllegalArgumentException("规则 " + id + " 缺 promql");
            }
            return new Rule(this);
        }
    }
}
