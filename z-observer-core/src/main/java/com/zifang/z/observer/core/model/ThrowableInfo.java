package com.zifang.z.observer.core.model;

/**
 * 异常信息。存栈而不存 Throwable 本身 —— 跨进程只能传可序列化的形态，
 * 而且序列化整个异常对象会把整棵 cause 链和 suppressed 一起拖走，上报体积不可控。
 *
 * <p>cause 深度截断是有意的：日志里最外层三帧通常已经指向问题所在，
 * 而有些异常链能套几十层，全量发会撑爆单帧上限还挤掉正常日志。
 */
public final class ThrowableInfo {

    public static final int MAX_CAUSE_DEPTH = 5;
    public static final int MAX_STACK_FRAMES = 64;

    private final String type;
    private final String message;
    private final String[] stackFrames;
    private final ThrowableInfo cause;

    public ThrowableInfo(String type, String message, String[] stackFrames, ThrowableInfo cause) {
        this.type = type;
        this.message = message;
        this.stackFrames = stackFrames;
        this.cause = cause;
    }

    /** 从 Throwable 提取，按上面两条上限截断。 */
    public static ThrowableInfo from(Throwable t) {
        if (t == null) {
            return null;
        }
        return from(t, 0);
    }

    private static ThrowableInfo from(Throwable t, int depth) {
        if (t == null || depth >= MAX_CAUSE_DEPTH) {
            return null;
        }
        StackTraceElement[] raw = t.getStackTrace();
        int take = Math.min(raw == null ? 0 : raw.length, MAX_STACK_FRAMES);
        String[] frames = new String[take];
        for (int i = 0; i < take; i++) {
            frames[i] = raw[i].toString();
        }
        ThrowableInfo cause = t.getCause() == t ? null : from(t.getCause(), depth + 1);
        return new ThrowableInfo(t.getClass().getName(), t.getMessage(), frames, cause);
    }

    public String type() {
        return type;
    }

    public String message() {
        return message;
    }

    public String[] stackFrames() {
        return stackFrames;
    }

    public ThrowableInfo cause() {
        return cause;
    }
}