package com.zifang.z.observer.admin.notify;

/** 通知发送失败。 */
public class NotifyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotifyException(String message) {
        super(message);
    }

    public NotifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
