package com.zifang.z.observer.admin.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 静默：在一段时间里、按标签选择性地压住通知。
 *
 * <p>静默只压通知，不改状态机 —— 被静默的告警照样进 FIRING，恢复时照样发恢复通知。
 * 这一点很关键：静默是「这段时间别烦我」，不是「这段时间当作没这回事」。
 * 如果静默期间不记状态，安静两小时后解静默，系统会对一堆早就恢复的问题发一轮
 * 过期告警，值班的人看到的是一屏已经修好的故障。
 */
public final class Silence {

    private final String id;
    private final List<String> matchers;
    private final long startMillis;
    private final long endMillis;
    private final String reason;
    private final String createdBy;

    public Silence(String id, List<String> matchers, long startMillis, long endMillis,
                   String reason, String createdBy) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("静默 id 必填");
        }
        if (endMillis <= startMillis) {
            throw new IllegalArgumentException("静默结束时间必须晚于开始时间");
        }
        this.id = id;
        this.matchers = Collections.unmodifiableList(new ArrayList<String>(matchers));
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.reason = reason == null ? "" : reason;
        this.createdBy = createdBy == null ? "" : createdBy;
    }

    public String id() {
        return id;
    }

    public long startMillis() {
        return startMillis;
    }

    public long endMillis() {
        return endMillis;
    }

    public String reason() {
        return reason;
    }

    public String createdBy() {
        return createdBy;
    }

    public boolean activeAt(long nowMillis) {
        return nowMillis >= startMillis && nowMillis < endMillis;
    }

    /**
     * 是否压住这条告警。
     *
     * <p>matcher 形如 {@code app=z-gw} 或 {@code severity=critical}，全部命中才算匹配。
     * 空 matcher 列表 = 压住一切，这是有意允许的（就是「全员静默」那个开关），
     * 但它必须是一次显式配置的结果，而不是默认行为。
     */
    public boolean matches(Alert alert) {
        for (String matcher : matchers) {
            int eq = matcher.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("匹配器格式应为 name=value: " + matcher);
            }
            String name = matcher.substring(0, eq).trim();
            String want = matcher.substring(eq + 1).trim();
            String actual = alert.labels().get(name);
            if (actual == null || !actual.equals(want)) {
                return false;
            }
        }
        return true;
    }

    /** 压住这条告警的静默；没有则 null。 */
    public static Silence firstMatch(List<Silence> silences, Alert alert, long nowMillis) {
        for (Silence s : silences) {
            if (s.activeAt(nowMillis) && s.matches(alert)) {
                return s;
            }
        }
        return null;
    }

    /** 给通知正文用的一行说明，附在告警上让人知道「为什么我没收到」。 */
    public String describe() {
        return "静默 " + id + " (" + reason + ", " + createdBy + ")";
    }
}
