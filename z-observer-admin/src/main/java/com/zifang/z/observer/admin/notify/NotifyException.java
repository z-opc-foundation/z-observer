package com.zifang.z.observer.admin.notify;

/**
 * 通知发送失败。
 *
 * <p><b>失败要分两种：</b>
 * <ul>
 *   <li>「暂时不行」—— z-msg 连不上、5xx、超时 ⇒ 该重试；</li>
 *   <li>「这么发就是不行」—— 4xx（除 408/429）、凭据取不到、参数不合法
 *       ⇒ 重试不会变好，但<b>照样不许丢</b>，照样落盘待发、照样计数。</li>
 * </ul>
 * 分开不是为了少重试几次，而是为了让日志与台账能回答「是渠道抖动还是我们发错了」。
 */
public class NotifyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean permanent;

    public NotifyException(String message) {
        this(message, false, null);
    }

    public NotifyException(String message, Throwable cause) {
        this(message, false, cause);
    }

    public NotifyException(String message, boolean permanent) {
        this(message, permanent, null);
    }

    public NotifyException(String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.permanent = permanent;
    }

    /** true = 重试也不会变好（请求本身有问题）。 */
    public boolean permanent() {
        return permanent;
    }
}
