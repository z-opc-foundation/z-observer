package com.zifang.z.observer.admin.rule;

/**
 * 告警通知出口。
 *
 * <p>需求里写死「通知出口唯一走 z-msg 渠道屏蔽层」，所以这里只定义形状，
 * 由 admin 模块提供实现转发给 z-msg，凭据走 z-mist 取、不落明文。
 * 引擎本身不关心渠道，这样引擎的测试不需要任何通知侧依赖。
 */
public interface AlertNotifier {

    /**
     * 通知一条告警进入 FIRING。
     *
     * @param silencedBy 非 null 时表示这条被静默压住了 —— 仍然要实现，
     *                   但可以只记账不发消息。静默信息随通知落库，
     *                   事后能回答「当时为什么没人收到」。
     */
    void notifyFiring(Alert alert, Silence silencedBy);

    /** 通知一条告警恢复。 */
    void notifyResolved(Alert alert);
}
