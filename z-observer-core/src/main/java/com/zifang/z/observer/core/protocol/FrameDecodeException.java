package com.zifang.z.observer.core.protocol;

/** 解帧失败。收端遇到它应当丢掉这一帧并计数，不应当让连接挂掉。 */
public class FrameDecodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FrameDecodeException(String message) {
        super(message);
    }
}