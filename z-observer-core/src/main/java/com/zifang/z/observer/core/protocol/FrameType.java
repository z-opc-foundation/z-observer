package com.zifang.z.observer.core.protocol;

/**
 * 上报帧类型。
 *
 * <p>一个 type 对应一种 body 的 JSON 结构。收端按 type 分派，type 未知必须直接拒帧而不是跳过 ——
 * 未知 type 出现说明两侧版本已经错位，静默跳过会把数据丢了还看不出。
 */
public enum FrameType {

    /** 指标批次。 */
    METRIC_BATCH(1),

    /** 日志批次。 */
    LOG_BATCH(2),

    /** span 批次。 */
    SPAN_BATCH(3),

    /** 心跳，供收端判活而不是判数据有无。 */
    HEARTBEAT(4);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 按 code 反查，未知值返回 null（由调用方决定拒帧）。 */
    public static FrameType fromCode(int code) {
        for (FrameType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }
}