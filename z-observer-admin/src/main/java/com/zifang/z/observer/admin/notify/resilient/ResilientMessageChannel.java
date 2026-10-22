package com.zifang.z.observer.admin.notify.resilient;

import com.zifang.z.observer.admin.notify.AlertNotification;
import com.zifang.z.observer.admin.notify.MessageChannel;
import com.zifang.z.observer.admin.notify.NotifyException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 渠道不可用时的行为：**重试 + 本地落盘待发 + 后台补发**，绝不静默丢。
 *
 * <p>M1 验收第 4 条对这条的要求是「行为明确」，所以这里把三段写死成一条流水线：
 * <ol>
 *   <li><b>重试</b>：失败后按退避重试 {@code maxAttempts} 次（默认 3 次，含首次）。</li>
 *   <li><b>落盘待发</b>：仍然失败 ⇒ 把这条通知写进本地暂存目录，
 *       <b>不丢</b>。落盘本身失败会往上抛（连暂存都存不住是绝境，
 *       静默吞掉等于把「没送出去」伪装成「送出去了」）。</li>
 *   <li><b>后台补发</b>：定时扫暂存目录重投；送到就删文件。
 *       补发次数用尽的挪进 {@code failed/} 目录留痕，不删。</li>
 * </ol>
 *
 * <p><b>为什么不吞异常。</b> 上层（{@code AlertDispatcher}）按渠道统计成功/失败，
 * 本类把「暂时失败但已落盘」也算作失败上报 —— 让人知道这条还没送到，
 * 而不是以为已经送到了。
 */
public final class ResilientMessageChannel implements MessageChannel {

    private static final Logger LOG = LogManager.getLogger(ResilientMessageChannel.class);

    private final MessageChannel delegate;
    private final int maxAttempts;
    private final long backoffMillis;
    private final NotificationSpool spool;
    private final int maxReplayAttempts;

    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong retriedCount = new AtomicLong();
    private final AtomicLong spooledCount = new AtomicLong();
    private final AtomicLong replayedCount = new AtomicLong();
    private final AtomicLong replayFailedCount = new AtomicLong();

    public ResilientMessageChannel(MessageChannel delegate, int maxAttempts, long backoffMillis,
                                   NotificationSpool spool, int maxReplayAttempts) {
        if (delegate == null) {
            throw new IllegalArgumentException("必须有被包装的渠道");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("重试次数至少 1 次");
        }
        this.delegate = delegate;
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
        this.spool = spool;
        this.maxReplayAttempts = maxReplayAttempts;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public void send(String title, String body) {
        AlertNotification n = new AlertNotification(AlertNotification.Kind.FIRING,
                "-", "-", "-", "-", "-", "-", System.currentTimeMillis(), 0L, title, body, null);
        send(n);
    }

    @Override
    public void send(AlertNotification notification) {
        NotifyException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                delegate.send(notification);
                sentCount.incrementAndGet();
                return;
            } catch (NotifyException e) {
                last = e;
                if (e.permanent()) {
                    // 请求本身有问题，重试只是把同一个错再犯一遍
                    LOG.error("渠道 {} 拒绝投递（不再重试）: {}", name(), e.getMessage());
                    break;
                }
                if (attempt < maxAttempts) {
                    retriedCount.incrementAndGet();
                    LOG.warn("渠道 {} 投递失败，第 {}/{} 次重试: {}",
                            name(), Integer.valueOf(attempt), Integer.valueOf(maxAttempts), e.getMessage());
                    sleep(backoffMillis * attempt);
                }
            } catch (RuntimeException e) {
                last = new NotifyException("渠道 " + name() + " 抛出异常: " + e.getMessage(), false, e);
                if (attempt < maxAttempts) {
                    retriedCount.incrementAndGet();
                    LOG.warn("渠道 {} 投递异常，第 {}/{} 次重试: {}",
                            name(), Integer.valueOf(attempt), Integer.valueOf(maxAttempts), e.getMessage());
                    sleep(backoffMillis * attempt);
                }
            }
        }
        failedCount.incrementAndGet();
        spoolForResend(notification, last);
    }

    /**
     * 落盘待发。
     *
     * <p>落盘失败会抛出去：这一条必须让上层知道「连待发都没存住」。
     */
    private void spoolForResend(AlertNotification notification, NotifyException cause) {
        if (spool == null) {
            LOG.error("渠道 {} 投递失败且没有暂存目录，这条通知无处可存: {}",
                    name(), cause == null ? "未知原因" : cause.getMessage());
            return;
        }
        spool.spool(notification);
        spooledCount.incrementAndGet();
    }

    /**
     * 补发一轮暂存里的通知。
     *
     * <p>由后台定时任务调用。返回本轮送到的条数。
     */
    public int replayOnce() {
        if (spool == null) {
            return 0;
        }
        int delivered = 0;
        for (java.io.File f : spool.listPending()) {
            AlertNotification n;
            int attempts;
            try {
                n = spool.read(f);
                attempts = spool.readAttempts(f);
            } catch (Exception e) {
                LOG.error("待发通知读不出来，跳过: {}", f.getName());
                continue;
            }
            if (attempts >= maxReplayAttempts) {
                spool.markFailed(f);
                replayFailedCount.incrementAndGet();
                continue;
            }
            try {
                delegate.send(n);
                spool.remove(f);
                delivered++;
                replayedCount.incrementAndGet();
                LOG.info("待发通知补发成功: {}", f.getName());
            } catch (RuntimeException e) {
                attempts++;
                spool.writeAttempts(f, attempts);
                replayFailedCount.incrementAndGet();
                LOG.warn("待发通知补发仍失败（第 {}/{} 次）: {}",
                        Integer.valueOf(attempts), Integer.valueOf(maxReplayAttempts), e.getMessage());
            }
        }
        return delivered;
    }

    public long sentCount() {
        return sentCount.get();
    }

    public long failedCount() {
        return failedCount.get();
    }

    public long retriedCount() {
        return retriedCount.get();
    }

    /** 落盘待发的条数（累计）。 */
    public long spooledCount() {
        return spooledCount.get();
    }

    /** 补发成功的条数（累计）。 */
    public long replayedCount() {
        return replayedCount.get();
    }

    /** 补发仍失败的次数（累计）。 */
    public long replayFailedCount() {
        return replayFailedCount.get();
    }

    /** 当前待发条数。 */
    public int pendingCount() {
        return spool == null ? 0 : spool.pendingCount();
    }

    /** 补发次数用尽、判定未送达的条数。 */
    public int failedSpoolCount() {
        return spool == null ? 0 : spool.failedCount();
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
