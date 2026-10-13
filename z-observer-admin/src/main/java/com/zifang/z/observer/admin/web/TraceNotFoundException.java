package com.zifang.z.observer.admin.web;

/** 请求的链路不存在。映射成 404，与「采到了但是空的」区分开。 */
public class TraceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TraceNotFoundException(String traceId) {
        super("链路不存在（可能已过保留期被淘汰）: " + traceId);
    }
}
