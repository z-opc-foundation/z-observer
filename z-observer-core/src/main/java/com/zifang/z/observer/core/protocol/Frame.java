package com.zifang.z.observer.core.protocol;

/** 一帧完整的上报数据：头 + body。 */
public final class Frame {

    private final FrameHeader header;
    private final byte[] body;

    public Frame(FrameHeader header, byte[] body) {
        this.header = header;
        this.body = body;
    }

    public FrameHeader header() {
        return header;
    }

    public FrameType type() {
        return header.type();
    }

    public byte[] body() {
        return body;
    }
}