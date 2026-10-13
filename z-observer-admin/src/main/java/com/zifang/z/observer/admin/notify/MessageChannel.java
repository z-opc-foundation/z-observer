package com.zifang.z.observer.admin.notify;

/**
 * 通知渠道。形状对齐 z-msg 的渠道屏蔽层，admin 只依赖这个接口。
 *
 * <p>本仓不实现任何具体渠道：钉钉/短信/邮件的凭据一律走 z-mist 取，
 * z-observer 里不留明文（这也是全仓零明文那条规矩的要求）。
 * 真接 z-msg 时这里换成它的客户端 bean 即可，告警引擎与格式化都不用动。
 */
public interface MessageChannel {

    /** 渠道名，写进通知记录以便回答「这条是发到哪儿去的」。 */
    String name();

    /**
     * 发一条消息。
     *
     * @throws NotifyException 发送失败。调用方负责计数，不吞异常 ——
     *         「以为发出去了」是告警系统最不能有的状态。
     */
    void send(String title, String body);
}
