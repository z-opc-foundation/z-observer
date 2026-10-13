package com.zifang.z.observer.admin.rule;

/**
 * 告警状态机的状态。
 *
 * <p>两段确认在中间那一段：{@code PENDING} 表示「本轮条件成立，但还没到通知的时候」。
 * 少了它，一次网络抖动就是一条短信；有了它，抖一下在下一个周期就自己消掉了。
 *
 * <pre>
 *   OK --条件成立--> PENDING --下一轮仍成立--> FIRING --> 通知
 *  ^                    |                                    |
 *   |                    +--下一轮不成立--------------------+
 *   +----------------条件不成立------------------------------------+
 * </pre>
 *
 * <p>{@code FIRING -> RESOLVED} 单独一个态而不是直接回 OK：恢复通知是需求里明写的
 * 一条（「恢复通知跟随」），如果恢复和「一直正常」共用一个状态，
 * 引擎就分不清「要不要发恢复通知」。
 */
public enum AlertState {

    /** 条件不成立。 */
    OK,

    /** 本轮条件成立，等待确认。 */
    PENDING,

    /** 已确认成立，通知已发。 */
    FIRING,

    /** 已确认恢复，恢复通知已发。 */
    RESOLVED;

    public boolean isActive() {
        return this == PENDING || this == FIRING;
    }
}
