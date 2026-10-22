package com.zifang.z.observer.admin.scrape;

/**
 * 采集事件：一轮采集里发生的一件可留痕的事。
 *
 * <p><b>为什么要有「事件」而不是只有一个失败计数。</b> 「进程挂了 60 秒内到人」这条口径
 * 要的不是「失败过多少次」，而是<b>现在还挂不挂</b>。计数回答不了「现在」，
 * 事件能：每个 target 的最后一条事件就是它的当前状态。
 *
 * <p><b>抓不到必须是事件，不能是日志行。</b> 日志行只有人去翻日志才看得见；
 * 而 absent 告警的前提是「机器能读到「这个 target 抓不到」这件事」——
 * 所以它必须落成一个可查询的结构（{@link ScrapeStatusRegistry}），
 * 而不是一句 {@code LOG.warn}。
 */
public final class ScrapeEvent {

    /** 事件类型。 */
    public enum Type {
        /** 这一轮抓到了，且 exposition 解析零失败。 */
        SCRAPE_SUCCEEDED,
        /** 这一轮抓不到（连不上 / 超时 / 非 200）。 */
        SCRAPE_FAILED,
        /** 抓到了，但 exposition 解析有坏行 —— 数据不可信，按失败论。 */
        PARSE_FAILED,
        /** 这个 target 从发现结果里消失了（进程自己注销或被摘掉）。 */
        TARGET_VANISHED,
        /** 这个 target 又出现了（消失记录的销账）。 */
        TARGET_REAPPEARED
    }

    private final Type type;
    private final String targetId;
    private final long timestampMillis;
    private final String detail;
    private final int sampleCount;

    public ScrapeEvent(Type type, String targetId, long timestampMillis, String detail,
                       int sampleCount) {
        if (type == null) {
            throw new IllegalArgumentException("事件类型必填");
        }
        if (targetId == null || targetId.isEmpty()) {
            throw new IllegalArgumentException("target id 必填");
        }
        this.type = type;
        this.targetId = targetId;
        this.timestampMillis = timestampMillis;
        this.detail = detail == null ? "" : detail;
        this.sampleCount = sampleCount;
    }

    public static ScrapeEvent succeeded(String targetId, long timestampMillis, int sampleCount) {
        return new ScrapeEvent(Type.SCRAPE_SUCCEEDED, targetId, timestampMillis,
                "抓到 " + sampleCount + " 条样本", sampleCount);
    }

    public static ScrapeEvent failed(String targetId, long timestampMillis, String error) {
        return new ScrapeEvent(Type.SCRAPE_FAILED, targetId, timestampMillis, error, 0);
    }

    public static ScrapeEvent parseFailed(String targetId, long timestampMillis, String error) {
        return new ScrapeEvent(Type.PARSE_FAILED, targetId, timestampMillis, error, 0);
    }

    public static ScrapeEvent vanished(String targetId, long timestampMillis) {
        return new ScrapeEvent(Type.TARGET_VANISHED, targetId, timestampMillis,
                "从发现结果中消失", 0);
    }

    public static ScrapeEvent reappeared(String targetId, long timestampMillis) {
        return new ScrapeEvent(Type.TARGET_REAPPEARED, targetId, timestampMillis,
                "重新出现在发现结果中", 0);
    }

    public Type type() {
        return type;
    }

    public String targetId() {
        return targetId;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    /** 人类可读的原因（错误信息 / 说明）。 */
    public String detail() {
        return detail;
    }

    /** 这一轮解析出来的样本条数（失败事件恒为 0）。 */
    public int sampleCount() {
        return sampleCount;
    }

    /** 这一条是不是「抓不到」。 */
    public boolean isUnreachable() {
        return type == Type.SCRAPE_FAILED || type == Type.PARSE_FAILED
                || type == Type.TARGET_VANISHED;
    }

    @Override
    public String toString() {
        return timestampMillis + " " + type + " " + targetId
                + (detail.isEmpty() ? "" : " (" + detail + ")");
    }
}
