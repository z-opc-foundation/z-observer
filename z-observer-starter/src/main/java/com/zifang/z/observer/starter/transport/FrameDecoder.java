package com.zifang.z.observer.starter.transport;

import com.zifang.z.observer.core.protocol.Frame;
import com.zifang.z.observer.core.protocol.FrameCodec;
import com.zifang.z.observer.core.protocol.FrameDecodeException;
import com.zifang.z.observer.core.protocol.FrameHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * 按帧长拆包并校验的解码器。
 *
 * <p>帧头里带 bodyLength，所以不需要靠分隔符猜边界 —— 这是自定义协议比换行分隔省事的地方：
 * 日志正文里本来就有换行，按行拆包会把一条日志劈成两条，而且劈在哪里取决于内容。
 *
 * <p>解码失败一律<b>关闭连接</b>，不做"跳过这一帧继续读"。字节流已经错位了，
 * 继续解析只会把后面所有帧都解成垃圾；这类错误必须让连接重建来恢复。
 */
public class FrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < FrameHeader.HEADER_LENGTH) {
            return;
        }
        in.markReaderIndex();
        byte[] headerBytes = new byte[FrameHeader.HEADER_LENGTH];
        in.readBytes(headerBytes);
        FrameHeader header;
        try {
            header = FrameCodec.decodeHeader(headerBytes);
        } catch (FrameDecodeException e) {
            ctx.close();
            return;
        }
        int bodyLength = header.bodyLength();
        if (in.readableBytes() < bodyLength) {
            // body 还没到齐，退回去等下一次读；帧头已读走但 markReaderIndex 还在
            in.resetReaderIndex();
            return;
        }
        byte[] body = new byte[bodyLength];
        in.readBytes(body);
        try {
            out.add(FrameCodec.complete(headerBytes, body));
        } catch (FrameDecodeException e) {
            // 头合法但 body 校验失败(CRC 对不上)：帧内容不可信，同样只能断链重连
            ctx.close();
        }
    }
}