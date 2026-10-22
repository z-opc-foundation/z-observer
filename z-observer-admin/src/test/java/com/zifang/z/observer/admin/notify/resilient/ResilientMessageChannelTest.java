package com.zifang.z.observer.admin.notify.resilient;

import com.zifang.z.observer.admin.notify.AlertNotification;
import com.zifang.z.observer.admin.notify.MessageChannel;
import com.zifang.z.observer.admin.notify.NotifyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1 验收第 4 条：<b>z-msg 不可用时的行为明确（重试 + 本地落盘待发，不静默丢）。</b>
 *
 * <p>这三段必须能分开验证：重试了几次、落盘了几条、补发之后还在不在。
 */
class ResilientMessageChannelTest {

    private static AlertNotification notification() {
        return new AlertNotification(AlertNotification.Kind.FIRING,
                "z-ctc-admin", "z-ctc-admin-abc", "process_absent", "进程消失",
                "critical", "0", 1_700_000_000_000L, 0L,
                "[告警] 进程消失", "正文", null);
    }

    /** 会失败 n 次、之后成功的渠道。 */
    private static final class FlakyChannel implements MessageChannel {
        private final AtomicInteger calls = new AtomicInteger();
        private final int failTimes;
        private final List<String> sent = new ArrayList<String>();

        FlakyChannel(int failTimes) {
            this.failTimes = failTimes;
        }

        @Override
        public String name() {
            return "flaky";
        }

        @Override
        public void send(String title, String body) {
            calls.incrementAndGet();
            throw new NotifyException("z-msg 不可达", false);
        }

        @Override
        public void send(AlertNotification n) {
            calls.incrementAndGet();
            if (calls.get() <= failTimes) {
                throw new NotifyException("z-msg 不可达", false);
            }
            sent.add(n.title());
        }
    }

    @Test
    void 重试成功就不落盘(@TempDir File dir) {
        FlakyChannel delegate = new FlakyChannel(1);
        NotificationSpool spool = new NotificationSpool(new File(dir, "spool"));
        ResilientMessageChannel c = new ResilientMessageChannel(delegate, 3, 1, spool, 5);

        c.send(notification());

        assertThat(delegate.calls.get()).isEqualTo(2);
        assertThat(c.sentCount()).isEqualTo(1);
        assertThat(c.failedCount()).isZero();
        assertThat(c.retriedCount()).isEqualTo(1);
        assertThat(c.spooledCount()).isZero();
        assertThat(spool.pendingCount()).isZero();
    }

    @Test
    void 重试用尽就落盘待发_不静默丢(@TempDir File dir) throws Exception {
        FlakyChannel delegate = new FlakyChannel(99);
        NotificationSpool spool = new NotificationSpool(new File(dir, "spool"));
        ResilientMessageChannel c = new ResilientMessageChannel(delegate, 3, 1, spool, 5);

        c.send(notification());

        // 重试 3 次（首次 + 2 次重试）
        assertThat(delegate.calls.get()).isEqualTo(3);
        assertThat(c.failedCount()).isEqualTo(1);
        assertThat(c.spooledCount()).isEqualTo(1);
        assertThat(spool.pendingCount()).isEqualTo(1);
        // 落盘的是这条通知本身，不是空壳
        AlertNotification back = spool.read(spool.listPending()[0]);
        assertThat(back.app()).isEqualTo("z-ctc-admin");
        assertThat(back.instance()).isEqualTo("z-ctc-admin-abc");
        assertThat(back.body()).isEqualTo("正文");
    }

    @Test
    void 补发成功后待发目录清空(@TempDir File dir) {
        final AtomicInteger calls = new AtomicInteger();
        MessageChannel delegate = new MessageChannel() {
            @Override
            public String name() {
                return "later";
            }

            @Override
            public void send(String title, String body) {
                send(notification());
            }

            @Override
            public void send(AlertNotification n) {
                if (calls.incrementAndGet() <= 3) {
                    throw new NotifyException("z-msg 不可达", false);
                }
            }
        };
        NotificationSpool spool = new NotificationSpool(new File(dir, "spool"));
        ResilientMessageChannel c = new ResilientMessageChannel(delegate, 3, 1, spool, 5);

        c.send(notification());
        assertThat(spool.pendingCount()).isEqualTo(1);

        int delivered = c.replayOnce();

        assertThat(delivered).isEqualTo(1);
        assertThat(spool.pendingCount()).isZero();
        assertThat(c.replayedCount()).isEqualTo(1);
    }

    @Test
    void 补发次数用尽标记未送达_不删(@TempDir File dir) {
        MessageChannel alwaysFail = new MessageChannel() {
            @Override
            public String name() {
                return "dead";
            }

            @Override
            public void send(String title, String body) {
                throw new NotifyException("z-msg 不可达", false);
            }

            @Override
            public void send(AlertNotification n) {
                throw new NotifyException("z-msg 不可达", false);
            }
        };
        NotificationSpool spool = new NotificationSpool(new File(dir, "spool"));
        ResilientMessageChannel c = new ResilientMessageChannel(alwaysFail, 1, 1, spool, 2);

        c.send(notification());
        c.replayOnce();   // 第 1 次补发：仍失败 → 计数 1
        c.replayOnce();   // 第 2 次补发：仍失败 → 计数 2

        c.replayOnce();   // 次数用尽 → 标记未送达

        assertThat(spool.pendingCount()).isZero();
        assertThat(spool.failedCount()).isEqualTo(1);
        assertThat(c.failedSpoolCount()).isEqualTo(1);
    }

    @Test
    void 永久性失败不重试但仍落盘(@TempDir File dir) {
        final AtomicInteger calls = new AtomicInteger();
        MessageChannel permanent = new MessageChannel() {
            @Override
            public String name() {
                return "perm";
            }

            @Override
            public void send(String title, String body) {
                calls.incrementAndGet();
                throw new NotifyException("z-msg 拒绝投递 HTTP 400", true);
            }

            @Override
            public void send(AlertNotification n) {
                calls.incrementAndGet();
                throw new NotifyException("z-msg 拒绝投递 HTTP 400", true);
            }
        };
        NotificationSpool spool = new NotificationSpool(new File(dir, "spool"));
        ResilientMessageChannel c = new ResilientMessageChannel(permanent, 3, 1, spool, 5);

        c.send(notification());

        assertThat(calls.get()).isEqualTo(1);
        assertThat(c.retriedCount()).isZero();
        // 但依然不许丢
        assertThat(c.spooledCount()).isEqualTo(1);
        assertThat(spool.pendingCount()).isEqualTo(1);
    }
}
