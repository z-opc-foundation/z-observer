package com.zifang.z.observer.admin.scrape;

/**
 * 一个采集目标的当前状态。
 *
 * <p>判据只有一条：<b>最后一条事件是「抓得到」还是「抓不到」</b>。
 * 计数（失败过多少次）回答不了「现在还挂不挂」，而 absent 告警要的恰恰是「现在」。
 *
 * <p>状态是<b>可变的、随采集轮次推进的</b>，不是历史：{@code lastSuccessMillis} 与
 * {@code lastFailureMillis} 谁更晚，谁就是当下。这也让「抓不到」与「又抓到了」
 * 不需要额外的状态机 —— 销账靠时间戳比较，不靠标志位。
 */
public final class ScrapeStatus {

    private final String targetId;

    private volatile long lastSuccessMillis = -1L;
    private volatile long lastFailureMillis = -1L;
    private volatile int consecutiveFailures;
    private volatile int lastSampleCount;
    private volatile String lastError = "";
    private volatile ScrapeEvent.Type lastEventType;
    private volatile long lastEventMillis = -1L;

    public ScrapeStatus(String targetId) {
        if (targetId == null || targetId.isEmpty()) {
            throw new IllegalArgumentException("target id 必填");
        }
        this.targetId = targetId;
    }

    /**
     * 拷贝一份。
     *
     * <p>快照必须是拷贝而不是引用：状态是随采集轮次推进的可变对象，
     * 把活对象交给调用方，它手里的「上一轮状态」会被下一轮悄悄改写 ——
     * 排障时看到的数字和当时记下来的数字对不上，是最难查的一类问题。
     */
    ScrapeStatus(ScrapeStatus other) {
        this.targetId = other.targetId;
        this.lastSuccessMillis = other.lastSuccessMillis;
        this.lastFailureMillis = other.lastFailureMillis;
        this.consecutiveFailures = other.consecutiveFailures;
        this.lastSampleCount = other.lastSampleCount;
        this.lastError = other.lastError;
        this.lastEventType = other.lastEventType;
        this.lastEventMillis = other.lastEventMillis;
    }

    public String targetId() {
        return targetId;
    }

    /** 最后一次成功采集的时刻；从来没有过就是 -1。 */
    public long lastSuccessMillis() {
        return lastSuccessMillis;
    }

    /** 最后一次抓不到的时刻；从来没有过就是 -1。 */
    public long lastFailureMillis() {
        return lastFailureMillis;
    }

    /** 连续抓不到几次。 */
    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** 最近一轮解析出来的样本条数。 */
    public int lastSampleCount() {
        return lastSampleCount;
    }

    /** 最近一次抓不到的原因。 */
    public String lastError() {
        return lastError;
    }

    public ScrapeEvent.Type lastEventType() {
        return lastEventType;
    }

    public long lastEventMillis() {
        return lastEventMillis;
    }

    /**
     * 现在抓得到吗。
     *
     * <p>判据是「最后一条事件是不是不可达」——不是「成功过没有」：
     * 一个曾经抓得到、现在抓不到的 target 必须算不可达，
     * 否则它会带着历史成功记录一直显示健康。
     */
    public boolean isUp() {
        ScrapeEvent.Type t = lastEventType;
        if (t == null) {
            return false;
        }
        return !ScrapeEvent.Type.SCRAPE_FAILED.equals(t)
                && !ScrapeEvent.Type.PARSE_FAILED.equals(t)
                && !ScrapeEvent.Type.TARGET_VANISHED.equals(t);
    }

    /** 现在缺席吗（抓不到）—— 供 absent 规则消费的那个判据。 */
    public boolean isAbsent() {
        return !isUp();
    }

    /** 吃进一条事件，更新状态。 */
    void apply(ScrapeEvent event) {
        this.lastEventType = event.type();
        this.lastEventMillis = event.timestampMillis();
        switch (event.type()) {
            case SCRAPE_SUCCEEDED:
                this.lastSuccessMillis = event.timestampMillis();
                this.consecutiveFailures = 0;
                this.lastSampleCount = event.sampleCount();
                this.lastError = "";
                break;
            case SCRAPE_FAILED:
            case PARSE_FAILED:
                this.lastFailureMillis = event.timestampMillis();
                this.consecutiveFailures++;
                this.lastError = event.detail();
                break;
            case TARGET_VANISHED:
                this.lastFailureMillis = event.timestampMillis();
                this.consecutiveFailures++;
                this.lastError = event.detail();
                break;
            case TARGET_REAPPEARED:
                // 只是「销账」，状态由紧接着的那次成功采集来定；
                // 这里不动 lastFailureMillis —— 它的意义是「上次抓不到是什么时候」，
                // 抹掉它会让排障时看不出这个 target 抖过。
                break;
            default:
                break;
        }
    }

    @Override
    public String toString() {
        return targetId + " up=" + isUp() + " consecutiveFailures=" + consecutiveFailures
                + " lastError=" + lastError;
    }
}
