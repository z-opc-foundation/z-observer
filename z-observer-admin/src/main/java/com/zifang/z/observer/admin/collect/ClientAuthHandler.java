package com.zifang.z.observer.admin.collect;

import com.zifang.z.observer.core.protocol.CollectorAuth;
import com.zifang.z.observer.core.protocol.FrameHeader;
import com.zifang.z.observer.core.protocol.FrameType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 采集连接的**客户端鉴权闸门**。
 *
 * <p><b>没有这道闸门时的暴露面</b>：帧协议只校验 magic、不校验任何客户端身份
 * ⇒ 任何能连上 14300 的主机都能往生产日志/链路库投递任意帧
 * ⇒ 既是日志注入面，也是存储耗尽面。
 *
 * <p><b>闸门规则</b>：
 * <ol>
 *   <li>连接上的<b>第一帧</b>必须是 {@link FrameType#AUTH}，否则断连；</li>
 *   <li>AUTH body 必须是 {@code nonce || HMAC-SHA256(secret, nonce)}，密钥不对断连；</li>
 *   <li>nonce <b>不许重放</b>：见过的 nonce 再来一次，断连；</li>
 *   <li>通过之后本处理器把自己从 pipeline 里摘掉 ⇒ 后续帧走正常分派，
 *       不给每帧都算一次 HMAC（那是白烧 CPU，也白给攻击者反复试探的机会）。</li>
 * </ol>
 *
 * <p><b>失败一律断连</b>，不「先收下这帧再说」：字节流一旦不可信，
 * 后面每一帧的处理都建立在不可信的前提上。
 *
 * <p>密钥从构造参数进来（由配置层从**环境变量 / Secret** 取），
 * 这里既不读环境变量也不读配置文件 ⇒ 密钥不进仓、不进日志。
 */
final class ClientAuthHandler extends ChannelInboundHandlerAdapter {

    /** 通道上的「已鉴权」标记。 */
    static final AttributeKey<Boolean> AUTHENTICATED = AttributeKey.valueOf("z.observer.collector.authed");

    /** 帧类型在帧头里的偏移：magic(2) + ver(1) + type(1)。 */
    private static final int TYPE_OFFSET = 3;

    private final byte[] secret;

    /**
     * 见过的 nonce（防重放）。
     *
     * <p>用 LRU 链表（{@link LinkedHashMap} 的 removeEldestEntry）而不是无限 Set：
     * 无界的 Set 会被攻击者拿重放刷成内存泄漏，有界的 LRU 只会让「很老的重放」有机会
     * 混进来 —— 而重放要成功还必须同时拿到同一个 nonce 的合法 HMAC，
     * 那种情况下它本来就是合法客户端。
     */
    private final Map<String, Boolean> seenNonces = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 1024;
        }
    };

    private boolean authenticated;

    ClientAuthHandler(byte[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("共享密钥必填");
        }
        this.secret = secret.clone();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (authenticated) {
            ctx.fireChannelRead(msg);
            return;
        }
        byte[] raw = toBytes(msg);
        if (raw == null || raw.length < FrameHeader.HEADER_LENGTH) {
            // 连帧头都不够 ⇒ 不是本协议的客户端
            ctx.close();
            return;
        }
        int typeCode = raw[TYPE_OFFSET] & 0xFF;
        FrameType type = FrameType.fromCode(typeCode);
        if (type != FrameType.AUTH) {
            // 第一帧不是 AUTH ⇒ 未鉴权的客户端 ⇒ 断连（不是「跳过这帧继续」）
            ctx.close();
            return;
        }
        byte[] body = Arrays.copyOfRange(raw, FrameHeader.HEADER_LENGTH, raw.length);
        if (!CollectorAuth.verify(secret, body)) {
            ctx.close();
            return;
        }
        byte[] nonce = CollectorAuth.nonceOf(body);
        if (nonce != null && !rememberNonce(nonce)) {
            // 重放 ⇒ 断连
            ctx.close();
            return;
        }
        authenticated = true;
        ctx.channel().attr(AUTHENTICATED).set(Boolean.TRUE);
        // 摘掉自己：后续帧直接进分派
        ctx.pipeline().remove(this);
        // AUTH 帧本身不交给分派（它不是数据）
    }

    /** 记下 nonce；见过的（含本次）返回 false。 */
    private boolean rememberNonce(byte[] nonce) {
        String key = toHex(nonce);
        synchronized (seenNonces) {
            if (seenNonces.containsKey(key)) {
                return false;
            }
            seenNonces.put(key, Boolean.TRUE);
            return true;
        }
    }

    private static byte[] toBytes(Object msg) {
        if (msg instanceof byte[]) {
            return (byte[]) msg;
        }
        if (msg instanceof ByteBuf) {
            ByteBuf buf = ((ByteBuf) msg).copy();
            try {
                byte[] out = new byte[buf.readableBytes()];
                buf.readBytes(out);
                return out;
            } finally {
                buf.release();
            }
        }
        return null;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
