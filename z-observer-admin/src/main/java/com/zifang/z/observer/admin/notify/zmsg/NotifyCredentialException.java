package com.zifang.z.observer.admin.notify.zmsg;

/**
 * 取凭据失败。
 *
 * <p>和「发送失败」分开：前者是「凭据这侧的问题」（z-mist 挂了 / 密钥不存在），
 * 后者是「渠道这侧的问题」。两者都要计数、都不许静默吞掉，
 * 但责任面不同，日志里得能分开看。
 */
public class NotifyCredentialException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotifyCredentialException(String message) {
        super(message);
    }

    public NotifyCredentialException(String message, Throwable cause) {
        super(message, cause);
    }
}
