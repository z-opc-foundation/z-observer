package com.zifang.z.observer.starter.transport;

import com.zifang.z.observer.core.protocol.Frame;
import com.zifang.z.observer.core.protocol.FrameCodec;
import com.zifang.z.observer.core.protocol.FrameHeader;
import com.zifang.z.observer.core.protocol.FrameType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameDecoderTest {

    private static byte[] body(String s) {
        return s.getBytes(Charset.forName("UTF-8"));
    }

    @Test
    void 完整帧一次解出() {
        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ch.writeInbound(Unpooled.wrappedBuffer(FrameCodec.encode(FrameType.LOG_BATCH, body("hi"))));
        Frame frame = ch.readInbound();
        assertEquals(FrameType.LOG_BATCH, frame.type());
        assertEquals("hi", new String(frame.body(), Charset.forName("UTF-8")));
        ch.finishAndReleaseAll();
    }

    @Test
    void 粘包一次解出多帧() {
        byte[] a = FrameCodec.encode(FrameType.LOG_BATCH, body("a"));
        byte[] b = FrameCodec.encode(FrameType.SPAN_BATCH, body("bb"));
        ByteBuf both = Unpooled.buffer();
        both.writeBytes(a);
        both.writeBytes(b);

        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ch.writeInbound(both);
        Frame first = ch.readInbound();
        assertEquals("a", new String(first.body(), Charset.forName("UTF-8")));
        Frame second = ch.readInbound();
        assertEquals(FrameType.SPAN_BATCH, second.type());
        assertEquals("bb", new String(second.body(), Charset.forName("UTF-8")));
        assertNull(ch.readInbound());
        ch.finishAndReleaseAll();
    }

    @Test
    void 半包先不出帧等下一次() {
        byte[] full = FrameCodec.encode(FrameType.LOG_BATCH, body("完整的一条"));
        ByteBuf partial = Unpooled.wrappedBuffer(full, 0, FrameHeader.HEADER_LENGTH + 3);
        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ch.writeInbound(partial);
        assertNull(ch.readInbound(), "body 不齐时不能产出半帧");

        ch.writeInbound(Unpooled.wrappedBuffer(
                full, FrameHeader.HEADER_LENGTH + 3, full.length - FrameHeader.HEADER_LENGTH - 3));
        Frame frame = ch.readInbound();
        assertEquals("完整的一条", new String(frame.body(), Charset.forName("UTF-8")));
        ch.finishAndReleaseAll();
    }

    @Test
    void 头不到12字节时什么都不产出() {
        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x5A, 0x4F, 1}));
        assertNull(ch.readInbound());
        ch.finishAndReleaseAll();
    }

    @Test
    void 空body合法_心跳帧就是空的() {
        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ch.writeInbound(Unpooled.wrappedBuffer(FrameCodec.encode(FrameType.HEARTBEAT, null)));
        Frame frame = ch.readInbound();
        assertEquals(FrameType.HEARTBEAT, frame.type());
        assertEquals(0, frame.body().length);
        ch.finishAndReleaseAll();
    }

    @Test
    void body损坏时关闭连接而不是解出错数据() {
        byte[] good = FrameCodec.encode(FrameType.LOG_BATCH, body("original"));
        // 改掉 body 的一个字节，让 CRC 对不上，但保持帧长度不变
        good[good.length - 1] ^= 0xFF;

        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ch.writeInbound(Unpooled.wrappedBuffer(good));
        assertNull(ch.readInbound(), "校验失败绝不能放出数据");
        assertTrue(!ch.isOpen(), "字节流已不可信, 必须断链");
        ch.finishAndReleaseAll();
    }

    @Test
    void 非法magic直接断链() {
        ByteBuf bad = Unpooled.buffer();
        bad.writeBytes(FrameCodec.encode(FrameType.LOG_BATCH, body("x")));
        bad.setByte(0, 0x00);

        EmbeddedChannel ch = new EmbeddedChannel(new FrameDecoder());
        ch.writeInbound(bad);
        assertNull(ch.readInbound());
        assertTrue(!ch.isOpen());
        ch.finishAndReleaseAll();
    }
}