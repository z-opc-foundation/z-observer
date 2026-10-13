package com.zifang.z.observer.core.protocol;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * 帧编解码。纯字节层，不依赖 Netty —— Netty 只负责把字节流切成一帧帧的完整 buffer，
 * 切完之后的所有语义都在这里，两侧共用同一份实现。
 *
 * <p>解码对每一处可能出错的地方都显式报错，不做「尽力而为」的修补：一个长度字段被写坏时，
 * 顺着它去读会拿到一整段错位的内存，之后每个字段都是错的、错误还会以数据损坏的形式传播很久。
 * 这里宁可一帧丢掉。
 */
public final class FrameCodec {

    private FrameCodec() {
    }

    public static byte[] encode(FrameType type, byte[] body) {
        if (type == null) {
            throw new IllegalArgumentException("type 不能为 null");
        }
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > FrameHeader.MAX_BODY_LENGTH) {
            throw new IllegalArgumentException(
                    "body 超过上限: " + payload.length + " > " + FrameHeader.MAX_BODY_LENGTH);
        }
        ByteBuffer buf = ByteBuffer.allocate(FrameHeader.HEADER_LENGTH + payload.length);
        buf.putShort(FrameHeader.MAGIC);
        buf.put(FrameHeader.VERSION);
        buf.put((byte) type.code());
        buf.putInt(payload.length);
        buf.putInt((int) crc32(payload));
        buf.put(payload);
        return buf.array();
    }

    /**
     * 只解帧头。字节流解码时要先看头才知道还差多少 body，头不合法就不必等 body。
     *
     * @param headerBytes 恰好 {@link FrameHeader#HEADER_LENGTH} 字节
     * @throws FrameDecodeException 头不合法时抛出
     */
    public static FrameHeader decodeHeader(byte[] headerBytes) {
        if (headerBytes == null || headerBytes.length < FrameHeader.HEADER_LENGTH) {
            throw new FrameDecodeException("帧头长度不足: "
                    + (headerBytes == null ? "null" : headerBytes.length));
        }
        ByteBuffer buf = ByteBuffer.wrap(headerBytes);
        short magic = buf.getShort();
        if (magic != FrameHeader.MAGIC) {
            throw new FrameDecodeException(String.format("magic 不符: 0x%04X", magic));
        }
        byte version = buf.get();
        if (version != FrameHeader.VERSION) {
            throw new FrameDecodeException("版本不认识: " + version);
        }
        int typeCode = buf.get();
        FrameType type = FrameType.fromCode(typeCode);
        if (type == null) {
            throw new FrameDecodeException("帧类型不认识: " + typeCode);
        }
        int bodyLength = buf.getInt();
        if (bodyLength < 0 || bodyLength > FrameHeader.MAX_BODY_LENGTH) {
            throw new FrameDecodeException("bodyLength 非法: " + bodyLength);
        }
        int checksum = buf.getInt();
        return new FrameHeader(version, type, bodyLength, checksum);
    }

    /** 校验 body 与头声明一致，并组装成完整帧。 */
    public static Frame complete(byte[] headerBytes, byte[] body) {
        FrameHeader header = decodeHeader(headerBytes);
        if (body.length != header.bodyLength()) {
            throw new FrameDecodeException(
                    "body 长度与头声明不符: 实际 " + body.length + " 期望 " + header.bodyLength());
        }
        int actual = (int) crc32(body);
        if (header.checksum() != actual) {
            throw new FrameDecodeException(String.format("校验和不符: 头声明 0x%08X 实算 0x%08X",
                    header.checksum(), actual));
        }
        return new Frame(header, body);
    }

    /**
     * 解一帧。传入的数组必须恰好是一整帧（头 + body），长度不对直接报错。
     *
     * @throws FrameDecodeException 头不合法、版本不认识、校验和不符时抛出
     */
    public static Frame decode(byte[] raw) {
        if (raw == null || raw.length < FrameHeader.HEADER_LENGTH) {
            throw new FrameDecodeException("帧长度不足: " + (raw == null ? "null" : raw.length));
        }
        FrameHeader header = decodeHeader(raw);
        int bodyLength = header.bodyLength();
        if (raw.length != FrameHeader.HEADER_LENGTH + bodyLength) {
            throw new FrameDecodeException(
                    "帧长度与头声明不符: 实际 " + raw.length + " 期望 " + (FrameHeader.HEADER_LENGTH + bodyLength));
        }
        byte[] body = new byte[bodyLength];
        System.arraycopy(raw, FrameHeader.HEADER_LENGTH, body, 0, bodyLength);
        return complete(raw, body);
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }
}