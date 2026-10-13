package com.zifang.z.observer.admin.collect;

import com.zifang.z.observer.core.protocol.FrameHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 收端帧解码。
 *
 * <p>帧头里带着 bodyLength，所以按长度切帧，不需要猜分隔符 —— 日志正文里到处是换行，
 * 按行切会把一条多行栈切成好几帧。
 *
 * <p><b>超长帧直接断连。</b> 上限以上的帧说明对端要么坏了要么在骗资源；继续读下去
 * 只是把内存耗光。断连让对端的连接重建逻辑接管，比在这里读一个几 GB 的长度字段安全。
 */
final class FrameDecoder extends ByteToMessageDecoder {

    private final int maxFrameBytes;

    FrameDecoder(int maxFrameBytes) {
        this.maxFrameBytes = maxFrameBytes;
    }

    private static final int BODY_LENGTH_OFFSET = 4; // magic(2) + ver(1) + type(1)

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < FrameHeader.HEADER_LENGTH) {
            return;
        }
        in.markReaderIndex();
        short magic = in.getShort(in.readerIndex());
        if (magic != FrameHeader.MAGIC) {
            // 不是本协议：没有回退到「找下一个 magic」，因为那等于让一个坏连接
            // 靠猜继续跑。要么对端是我们，要么它不是 —— 两种都不该在这里猜。
            throw new IllegalStateException(String.format("magic 不符: 0x%04X", magic));
        }
        int bodyLength = in.getInt(in.readerIndex() + BODY_LENGTH_OFFSET);
        if (bodyLength < 0 || bodyLength > maxFrameBytes - FrameHeader.HEADER_LENGTH) {
            throw new IllegalStateException("bodyLength 越界: " + bodyLength);
        }
        int total = FrameHeader.HEADER_LENGTH + bodyLength;
        if (in.readableBytes() < total) {
            in.resetReaderIndex();
            return;
        }
        byte[] raw = new byte[total];
        in.readBytes(raw);
        out.add(raw);
    }
}
