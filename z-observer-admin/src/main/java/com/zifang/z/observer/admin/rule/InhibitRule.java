package com.zifang.z.observer.admin.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 抑制规则：源告警存在时，压住目标告警的通知。
 *
 * <p>典型场景是「集群整体挂了」会同时触发每个 pod 的 absent ——
 * 200 条一模一样的短信没有人看得完，而真正该叫人的只有「集群不可用」那一条。
 * 抑制规则就是把这 200 条压掉。
 *
 * <p><b>同一条规则不能抑制自己。</b> 没做这个检查的话，
 * 「pod A 挂了导致 pod A 的错误率也高」会让错误率告警被自己压掉，
 * 而错误率那条往往才是根因。
 */
public final class InhibitRule {

    private final String id;
    private final String sourceRuleId;
    private final String targetRuleId;
    private final String equalLabel;
    private final boolean sameNamespace;

    private InhibitRule(String id, String sourceRuleId, String targetRuleId,
                        String equalLabel, boolean sameNamespace) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("抑制规则 id 必填");
        }
        if (sourceRuleId == null || sourceRuleId.equals(targetRuleId)) {
            throw new IllegalArgumentException(
                    "抑制源规则必填且不能与目标相同（自己抑制自己会把根因压掉）: "
                            + sourceRuleId + " -> " + targetRuleId);
        }
        if (targetRuleId == null || targetRuleId.isEmpty()) {
            throw new IllegalArgumentException("抑制目标规则必填");
        }
        this.id = id;
        this.sourceRuleId = sourceRuleId;
        this.targetRuleId = targetRuleId;
        this.equalLabel = equalLabel;
        this.sameNamespace = sameNamespace;
    }

    /** 两个规则必须在同一个标签上取值相同才算同一范围（同一个集群/同一个机房）。 */
    public static InhibitRule sameLabelOn(String id, String sourceRuleId,
                                          String targetRuleId, String label) {
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("equalLabel 必填");
        }
        return new InhibitRule(id, sourceRuleId, targetRuleId, label, true);
    }

    /** 不限标签：源规则任一实例存在就压住目标。 */
    public static InhibitRule any(String id, String sourceRuleId, String targetRuleId) {
        return new InhibitRule(id, sourceRuleId, targetRuleId, null, false);
    }

    public String id() {
        return id;
    }

    public String sourceRuleId() {
        return sourceRuleId;
    }

    public String targetRuleId() {
        return targetRuleId;
    }

    public String equalLabel() {
        return equalLabel;
    }

    /** target 告警是否被某条 firing 的 source 告警抑制。 */
    public boolean suppresses(Alert target, List<Alert> firingAlerts) {
        for (Alert source : firingAlerts) {
            if (!source.state().isActive()) {
                continue;
            }
            if (!source.ruleId().equals(sourceRuleId)) {
                continue;
            }
            if (!sameNamespace) {
                return true;
            }
            String a = source.labels().get(equalLabel);
            String b = target.labels().get(equalLabel);
            // 标签缺失时不抑制：宁可多叫一次，也不要在「两边都没有这个标签」这种
            // 情况下凭空推断出它们属于同一范围。
            if (a != null && a.equals(b)) {
                return true;
            }
        }
        return false;
    }
}
