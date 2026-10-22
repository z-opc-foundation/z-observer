package com.zifang.z.observer.admin.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理面入站鉴权配置（{@code z.observer.admin.auth.*}）。
 *
 * <p><b>这里没有 token 字段。</b> 密钥只从环境变量 / Secret 取
 * （见 {@code SecretSource}）—— 配置类里放凭据字段，全仓零明文那条硬规矩就破了。
 *
 * <p>{@code enabled} 默认 <b>true</b>：默认开着是「安全的那一边」。
 * 真要关掉（比如本地起一个空壳收端调试），显式写 {@code enabled=false}。
 */
@ConfigurationProperties(prefix = "z.observer.admin.auth")
public class AdminAuthProperties {

    /** 是否启用入站鉴权。默认 true（默认开着才是安全的那一边）。 */
    private boolean enabled = true;

    /**
     * 免鉴权的路径前缀。
     *
     * <p>只放**不泄漏任何业务数据**的健康检查类端点。
     * 采集面（k8s 探针）需要一条免鉴权的路，否则 Pod 永远不就绪 ——
     * 但那条路必须是「只答活着不活着」的健康端点，不能是业务查询面。
     */
    private List<String> excludedPaths = new ArrayList<String>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths == null ? new ArrayList<String>() : excludedPaths;
    }
}
