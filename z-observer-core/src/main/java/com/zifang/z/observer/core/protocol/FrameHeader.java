package com.zifang.z.observer.core.protocol;

/**
 * 帧定长头。12 字节，全部按大端写。
 *
 * <pre>
 *  0      2      3      4           8          12
 *  +------+------+------+-----------+----------+
 *  | magic| ver  | type | bodyLength | checksum  |
 *  +------+------+------+-----------+----------+
 * </pre>
 *
 * <p>checksum 是 body 的 CRC32，不含头本身 —— 头里的 bodyLength 已经决定了读多少，
 * 头被改坏会被长度检查或 magic 检查挡住，验 body 就够。
 *
 * <p>traceId 不占头位：它是 span 的业务属性，不是分帧元数据，塞进 body 的 span 结构更自然。
 */
public final class FrameHeader {

    /** 'Z' 'O'，用来在解包第一步就把非本协议的字节流挡掉。 */
    public static final short MAGIC = (short) 0x5A4F;

    public static final byte VERSION = 1;

    public static final int HEADER_LENGTH = 12;

    /** 最大 body 长度。日志批次是唯一可能撑大的一类，超出即拒帧。 */
    public static final int MAX_BODY_LENGTH = 4 * 1024 * 1024;

    private final byte version;
    private final FrameType type;
    private final int bodyLength;
    private final int checksum;

    public FrameHeader(byte version, FrameType type, int bodyLength, int checksum) {
        this.version = version;
        this.type = type;
        this.bodyLength = bodyLength;
        this.checksum = checksum;
    }

    public byte version() {
        return version;
    }

    public FrameType type() {
        return type;
    }

    public int bodyLength() {
        return bodyLength;
    }

    public int checksum() {
        return checksum;
    }
}