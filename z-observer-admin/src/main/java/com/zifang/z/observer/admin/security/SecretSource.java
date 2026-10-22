package com.zifang.z.observer.admin.security;

import java.nio.charset.StandardCharsets;

/**
 * 共享密钥的取值口：**只从环境变量 / Secret 取**。
 *
 * <p><b>为什么不做成配置项。</b> 本仓有一条硬规矩：配置文件里不出现凭据
 * （见 {@code ObserverAdminProperties} 的类注释：配置类里出现 token 字段，
 * 几乎必然在某次调试后被提交进 git）。所以：
 *
 * <ul>
 *   <li>配置类里只有「哪个环境变量名」这种**非凭据**信息；</li>
 *   <li>凭据的值由运行环境注入（K8s Secret → 环境变量）；</li>
 *   <li>取值后**只留在内存里**，不进日志、不进告警正文、不进落盘文件。</li>
 * </ul>
 *
 * <p>⚠ 本域的 z-mist 取密件器目前只接在通知出口上（{@code z.observer.notify.mist}），
 * 且生产上没配 z-mist 地址 ⇒ 鉴权密钥不能走 z-mist。
 * ⇒ 这里走环境变量。**将来要把本域接到 z-mist 时，只需把本类换成 z-mist 取数口**，
 * 调用方（{@code AdminAuthConfiguration} / {@code ObserverAdminConfiguration}）不用动。
 */
public final class SecretSource {

    /** 管理面入站鉴权的密钥环境变量名。 */
    public static final String ADMIN_AUTH_TOKEN_ENV = "Z_OBSERVER_ADMIN_AUTH_TOKEN";

    /** 采集口（客户端鉴权）的共享密钥环境变量名。 */
    public static final String COLLECTOR_AUTH_TOKEN_ENV = "Z_OBSERVER_COLLECTOR_TOKEN";

    private SecretSource() {
    }

    /**
     * 从环境变量取密钥。
     *
     * <p><b>取值顺序</b>：
     * <ol>
     *   <li>环境变量 {@code Z_OBSERVER_*} —— <b>生产路径</b>（K8s Secret 注入）；</li>
     *   <li>JVM 系统属性 {@code -DZ_OBSERVER_*} —— <b>进程级</b>覆盖，给本地/测试用。
     *       它不是配置文件项 ⇒ 不会因为「顺手写进 application.yml」而进仓。</li>
     * </ol>
     *
     * @return 密钥明文；没配或只有空白 ⇒ 返回 {@code null}（**不抛异常**：
     *         「没配」要由调用方决定是启动失败还是降级，工具类不该替它做主）。
     */
    public static String fromEnv(String envName) {
        if (envName == null || envName.isEmpty()) {
            return null;
        }
        String v = System.getenv(envName);
        if (v == null || v.trim().isEmpty()) {
            v = System.getProperty(envName);
        }
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /** 从环境变量取密钥的字节形式（UTF-8）。 */
    public static byte[] bytesFromEnv(String envName) {
        String v = fromEnv(envName);
        return v == null ? null : v.getBytes(StandardCharsets.UTF_8);
    }
}
