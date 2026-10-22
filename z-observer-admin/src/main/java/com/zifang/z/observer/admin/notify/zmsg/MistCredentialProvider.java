package com.zifang.z.observer.admin.notify.zmsg;

/**
 * 渠道凭据的取数口。
 *
 * <p><b>凭据只从 z-mist 取</b>（全仓零明文那条硬规矩）：收端不存 token/口令，
 * 只存一条 {@code credentialRef}（密钥的引用），运行时去 z-mist 要明文。
 *
 * <p>实现方拿到明文后<b>不许</b>写进日志、告警正文或任何落盘文件 ——
 * 拿到明文的那一刻它只应当出现在内存里那一条路径上。
 */
public interface MistCredentialProvider {

    /**
     * 按引用取明文凭据。
     *
     * @param credentialRef 密钥引用。格式 {@code group/namespace/secretKey}，
     *                      只有一段时按 {@code secretKey} 处理（group/namespace 走缺省）。
     * @return 明文凭据。
     * @throws NotifyCredentialException 取不到（z-mist 不可达 / 密钥不存在 / 鉴权失败）。
     */
    String resolve(String credentialRef);
}
