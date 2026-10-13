package com.zifang.z.observer.admin.config;

import com.zifang.z.observer.admin.rule.Condition;
import com.zifang.z.observer.admin.rule.Expression;
import com.zifang.z.observer.admin.rule.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条规则的可绑定声明式描述。
 *
 * <p>规则从 application.yml 装配而不是写死在代码里：M1 要交付的是「值班的人能改
 * 阈值和确认时长」，把规则写死成 Java 常量等于把这条能力砍掉。
 */
public class RuleSpec {

    private String id;
    private String name;
    private String severity = "warning";
    private String expression = "THRESHOLD";
    private String promql;
    private String condition = "GT";
    private double threshold;
    private long forDurationMillis = 30_000L;
    private long absentGraceMillis = 30_000L;
    private List<String> groupBy = new ArrayList<String>();
    private String summary;
    private String description;
    private List<SubConditionSpec> conditions = new ArrayList<SubConditionSpec>();

    /** 组合布尔的子条件。THRESHOLD / ABSENT 用不到。 */
    public static class SubConditionSpec {
        private String promql;
        private String condition = "GT";
        private double threshold;

        public String getPromql() {
            return promql;
        }

        public void setPromql(String promql) {
            this.promql = promql;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }
    }

    public Rule toRule() {
        Rule.Builder builder = Rule.builder()
                .id(id)
                .name(name == null ? id : name)
                .severity(severity)
                .expression(Expression.valueOf(expression))
                .condition(Condition.valueOf(condition))
                .threshold(threshold)
                .forDurationMillis(forDurationMillis)
                .absentGraceMillis(absentGraceMillis)
                .summary(summary)
                .description(description);
        if (promql != null) {
            builder.promql(promql);
        }
        if (groupBy != null && !groupBy.isEmpty()) {
            builder.groupBy(groupBy.toArray(new String[0]));
        }
        if (conditions != null) {
            for (SubConditionSpec sub : conditions) {
                builder.condition(new Rule.SubCondition(
                        sub.getPromql(),
                        Condition.valueOf(sub.getCondition() == null ? "GT" : sub.getCondition()),
                        sub.getThreshold()));
            }
        }
        return builder.build();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getPromql() {
        return promql;
    }

    public void setPromql(String promql) {
        this.promql = promql;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public long getForDurationMillis() {
        return forDurationMillis;
    }

    public void setForDurationMillis(long forDurationMillis) {
        this.forDurationMillis = forDurationMillis;
    }

    public long getAbsentGraceMillis() {
        return absentGraceMillis;
    }

    public void setAbsentGraceMillis(long absentGraceMillis) {
        this.absentGraceMillis = absentGraceMillis;
    }

    public List<String> getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(List<String> groupBy) {
        this.groupBy = groupBy;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<SubConditionSpec> getConditions() {
        return conditions;
    }

    public void setConditions(List<SubConditionSpec> conditions) {
        this.conditions = conditions;
    }
}
