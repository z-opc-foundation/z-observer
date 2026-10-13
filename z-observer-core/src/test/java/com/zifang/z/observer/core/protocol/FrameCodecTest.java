package com.zifang.z.observer.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameCodecTest {

    private static byte[] body(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void 编解码往返后头部与body一致() {
        byte[] payload = body("{\"samples\":[]}");
        Frame decoded = FrameCodec.decode(FrameCodec.encode(FrameType.METRIC_BATCH, payload));

        assertEquals(FrameType.METRIC_BATCH, decoded.type());
        assertEquals(FrameHeader.VERSION, decoded.header().version());
        assertEquals(payload.length, decoded.header().bodyLength());
        assertArrayEquals(payload, decoded.body());
    }

    @Test
    void 头部为12字节且magic在固定位置() {
        byte[] frame = FrameCodec.encode(FrameType.LOG_BATCH, body("x"));
        assertEquals(FrameHeader.HEADER_LENGTH + 1, frame.length);

        int magic = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
        assertEquals(FrameHeader.MAGIC, (short) magic);
        assertEquals(FrameHeader.VERSION, frame[2]);
        assertEquals((byte) FrameType.LOG_BATCH.code(), frame[3]);
    }

    @Test
    void 空body是合法的() {
        Frame decoded = FrameCodec.decode(FrameCodec.encode(FrameType.HEARTBEAT, null));
        assertEquals(FrameType.HEARTBEAT, decoded.type());
        assertEquals(0, decoded.body().length);
    }

    @Test
    void magic不对时拒帧而不是当数据读() {
        byte[] frame = FrameCodec.encode(FrameType.LOG_BATCH, body("x"));
        frame[0] = 0x00;
        FrameDecodeException e = assertThrows(FrameDecodeException.class, () -> FrameCodec.decode(frame));
        assertEquals(true, e.getMessage().contains("magic"));
    }

    @Test
    void 版本不认识时拒帧() {
        byte[] frame = FrameCodec.encode(FrameType.LOG_BATCH, body("x"));
        frame[2] = 99;
        FrameDecodeException e = assertThrows(FrameDecodeException.class, () -> FrameCodec.decode(frame));
        assertEquals(true, e.getMessage().contains("版本"));
    }

    @Test
    void 类型不认识时拒帧而不是跳过() {
        byte[] frame = FrameCodec.encode(FrameType.LOG_BATCH, body("x"));
        frame[3] = 77;
        FrameDecodeException e = assertThrows(FrameDecodeException.class, () -> FrameCodec.decode(frame));
        assertEquals(true, e.getMessage().contains("类型"));
    }

    @Test
    void body被改一个字节时校验和拦住() {
        byte[] frame = FrameCodec.encode(FrameType.SPAN_BATCH, body("{\"a\":1}"));
        frame[FrameHeader.HEADER_LENGTH] ^= 0x01;
        FrameDecodeException e = assertThrows(FrameDecodeException.class, () -> FrameCodec.decode(frame));
        assertEquals(true, e.getMessage().contains("校验和"));
    }

    @Test
    void 长度字段被改时先由长度自洽检查拦住() {
        byte[] frame = FrameCodec.encode(FrameType.METRIC_BATCH, body("12345"));
        // bodyLength 从 5 改成 999
        frame[4] = 0x00;
        frame[5] = 0x00;
        frame[6] = 0x03;
        frame[7] = (byte) 0xE7;
        FrameDecodeException e = assertThrows(FrameDecodeException.class, () -> FrameCodec.decode(frame));
        assertEquals(true, e.getMessage().contains("长度"));
    }

    @Test
    void 截断的帧拒帧() {
        byte[] frame = FrameCodec.encode(FrameType.LOG_BATCH, body("hello"));
        byte[] truncated = new byte[frame.length - 2];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);
        assertThrows(FrameDecodeException.class, () -> FrameCodec.decode(truncated));
    }

    @Test
    void 头本身不完整时拒帧() {
        assertThrows(FrameDecodeException.class, () -> FrameCodec.decode(new byte[5]));
        assertThrows(FrameDecodeException.class, () -> FrameCodec.decode(null));
    }

    @Test
    void body超上限时编不过去() {
        byte[] tooBig = new byte[FrameHeader.MAX_BODY_LENGTH + 1];
        assertThrows(IllegalArgumentException.class,
                () -> FrameCodec.encode(FrameType.LOG_BATCH, tooBig));
    }
}
