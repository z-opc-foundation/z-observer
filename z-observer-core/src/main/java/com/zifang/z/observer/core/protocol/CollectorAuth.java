package com.zifang.z.observer.core.protocol;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 采集端的客户端鉴权握手（共享密钥口径）。
 *
 * <p><b>为什么需要它。</b> 收端的采集口（默认 14300）在加这道闸门之前，
 * 帧协议只校验 magic、不校验任何客户端身份 ⇒ <b>任何能连上 TCP 的主机</b>
 * 都能往生产日志/链路库投递帧。那既是<b>日志注入</b>面，也是<b>存储耗尽</b>面
 * （往里灌垃圾日志把存储打满，告警数据同时被淹没）。
 *
 * <p><b>握手长什么样。</b> 连接建立后，客户端发的<b>第一帧</b>必须是
 * {@link FrameType#AUTH}，body 结构：
 *
 * <pre>
 *   body = nonce(16 字节) || HMAC-SHA256(secret, nonce)
 * </pre>
 *
 * <ul>
 *   <li><b>共享密钥不落到线上</b>：线上只有它的 HMAC，抓包拿到也反推不出密钥；
 *       密钥本身只来自环境变量 / Secret（不进仓、不进配置文件、不进日志）。</li>
 *   <li><b>每次握手换一个 nonce</b>：同一个密钥的两次握手线上载荷不同。</li>
 *   <li><b>收端拒重放</b>：收端记住最近见过的 nonce，重复的 nonce 一律断连
 *       ⇒ 抓包拿到一帧 AUTH 也不能重放出一条新连接。</li>
 *   <li><b>常数时间比较</b>：{@link MessageDigest#isEqual}，不留下按字节比对的时序侧信道。</li>
 * </ul>
 *
 * <p><b>不在 wire 上做「明文密钥」</b>，也不做 mTLS —— mTLS 要证书体系，
 * 那是另一件事；共享密钥 + HMAC 是这道闸门该有的最小可用形态。
 *
 * <p>两侧共用这一份实现（SDK 侧构造、收端侧校验），避免两边各写一遍协议漂移。
 */
public final class CollectorAuth {

    /** nonce 长度（字节）。 */
    public static final int NONCE_LENGTH = 16;

    /** HMAC 算法。 */
    public static final String ALGORITHM = "HmacSHA256";

    /** AUTH body 的总长度：nonce + HMAC 摘要。 */
    public static final int AUTH_BODY_LENGTH = NONCE_LENGTH + 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CollectorAuth() {
    }

    /**
     * 造一份 AUTH body：{@code nonce || HMAC(secret, nonce)}。
     *
     * @param secret 共享密钥（明文）。调用方负责它来自环境变量 / Secret。
     * @return 可直接放进 AUTH 帧 body 的字节。
     */
    public static byte[] buildAuthBody(byte[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("共享密钥必填");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        byte[] mac = hmac(secret, nonce);
        byte[] body = new byte[nonce.length + mac.length];
        System.arraycopy(nonce, 0, body, 0, nonce.length);
        System.arraycopy(mac, 0, body, nonce.length, mac.length);
        return body;
    }

    /**
     * 校验一份 AUTH body。
     *
     * <p><b>不抛异常，只给布尔</b>：校验失败在协议层就是「这不是我们的客户端」，
     * 抛异常会把「对端不友好」和「代码有 bug」混成同一件事。
     *
     * @param secret 共享密钥（明文）。
     * @param body   收到的 AUTH 帧 body。
     * @return true = 通过；false = 密钥不对 / 载荷不对。
     */
    public static boolean verify(byte[] secret, byte[] body) {
        if (secret == null || secret.length == 0 || body == null) {
            return false;
        }
        if (body.length != AUTH_BODY_LENGTH) {
            return false;
        }
        byte[] nonce = Arrays.copyOfRange(body, 0, NONCE_LENGTH);
        byte[] expected = hmac(secret, nonce);
        byte[] actual = Arrays.copyOfRange(body, NONCE_LENGTH, body.length);
        // 常数时间比较：不等长也要比（长度差已经挡在上面，这里长度必然相等）
        return MessageDigest.isEqual(expected, actual);
    }

    /** 取 AUTH body 里的 nonce（用于收端做重放判定）。 */
    public static byte[] nonceOf(byte[] body) {
        if (body == null || body.length < NONCE_LENGTH) {
            return null;
        }
        return Arrays.copyOfRange(body, 0, NONCE_LENGTH);
    }

    private static byte[] hmac(byte[] secret, byte[] nonce) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(nonce);
        } catch (Exception e) {
            // 唯一的可能是 JRE 少了 HmacSHA256 —— 那是环境坏了，不是对端坏了。
            throw new IllegalStateException("算 HMAC 失败: " + e.getMessage(), e);
        }
    }
}
