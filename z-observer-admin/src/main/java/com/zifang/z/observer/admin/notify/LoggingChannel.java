package com.zifang.z.observer.admin.notify;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 默认渠道：把告警写进收端自己的日志。
 *
 * <p>它不是「凑合用的实现」，而是接 z-msg 之前的<b>兜底</b>：
 * 渠道没配好时，告警至少还留在收端的日志里，不会因为「没渠道」而整条消失。
 * 真渠道接上后它仍然留着，作为最后一道落盘。
 */
public final class LoggingChannel implements MessageChannel {

    private static final Logger LOG = LogManager.getLogger(LoggingChannel.class);

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void send(String title, String body) {
        LOG.warn("[告警通知/{}] {}\n{}", name(), title, body);
    }
}
