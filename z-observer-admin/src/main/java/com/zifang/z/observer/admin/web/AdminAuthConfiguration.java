package com.zifang.z.observer.admin.web;

import com.zifang.z.observer.admin.security.SecretSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理面入站鉴权的装配。
 *
 * <p><b>失败的方向是「关」不是「开」。</b> 开了鉴权却没配密钥时，
 * 这里<b>启动就失败</b>（而不是「先不鉴权地跑起来」）——
 * 与本仓其它配置项的口径一致（「宁可启动失败并在配置里指名道姓」，
 * 见 {@code ObserverTransportAutoConfiguration#requireHost}）。
 * 理由很直接：一个「以为已经加了鉴权、其实没加」的进程，比一个起不来的进程危险得多。
 */
@Configuration
@EnableConfigurationProperties(AdminAuthProperties.class)
public class AdminAuthConfiguration {

    private static final Logger LOG = LogManager.getLogger(AdminAuthConfiguration.class);

    /**
     * 采集面（k8s 探针）用的免鉴权健康端点。
     *
     * <p>⚠ 它<b>只能</b>答「活着不活着」，不能带任何业务数据 ——
     * 免鉴权的端点就是公开面。
     */
    public static final String INTERNAL_HEALTH_PATH = "/api/internal/health";

    /**
     * actuator 存活端点（免鉴权）。
     *
     * <p>它是<b>组织通用口径</b>（别的仓的通用探针都打 {@code /actuator/health}），
     * 而通用探针不会带 Authorization 头 ⇒ 把它挡在闸门外，通用探针才能用。
     *
     * <p>它<b>只</b>答「活着不活着」：暴露面在 {@code application.yml} 里钉死为
     * {@code health,info}，且 {@code show-details: never} ⇒ 响应体只有
     * {@code {"status":"UP"}}，没有组件明细、没有业务数据。
     * （要业务数据请打带鉴权的 {@code /api/*}。）
     */
    public static final String ACTUATOR_HEALTH_PATH = "/actuator/health";

    /** 过滤器顺序：越靠前越先执行。 */
    private static final int AUTH_FILTER_ORDER = 10;

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(AdminAuthProperties props) {
        List<String> excluded = new ArrayList<String>();
        excluded.add(INTERNAL_HEALTH_PATH);
        excluded.add(ACTUATOR_HEALTH_PATH);
        if (props.getExcludedPaths() != null) {
            excluded.addAll(props.getExcludedPaths());
        }

        if (!props.isEnabled()) {
            // 显式关掉：这是调试用的口子，必须**吵**——不然它会安静地留在生产里。
            LOG.warn("管理面入站鉴权已被显式关闭（z.observer.admin.auth.enabled=false）"
                    + " —— 任何能连上本端口的主机都能读写告警规则/静默/抑制");
            return registration(new AdminAuthFilter("disabled", excluded));
        }

        String token = SecretSource.fromEnv(SecretSource.ADMIN_AUTH_TOKEN_ENV);
        if (token == null) {
            throw new IllegalStateException(
                    "管理面入站鉴权已启用但没配密钥：环境变量 "
                            + SecretSource.ADMIN_AUTH_TOKEN_ENV
                            + " 必须有值（生产从 Secret 注入）。"
                            + "拒绝以「无鉴权」姿态启动 —— 要临时关掉请显式设 "
                            + "z.observer.admin.auth.enabled=false");
        }
        // ⚠ 日志里只出现「开了」和「免鉴权路径」，**不出现密钥本身**。
        LOG.info("管理面入站鉴权已启用（bearer），免鉴权路径：{}", excluded);
        return registration(new AdminAuthFilter(token, excluded));
    }

    private FilterRegistrationBean<AdminAuthFilter> registration(AdminAuthFilter filter) {
        FilterRegistrationBean<AdminAuthFilter> bean = new FilterRegistrationBean<AdminAuthFilter>(filter);
        bean.setOrder(AUTH_FILTER_ORDER);
        // 拦全部 URL：读面与写面一起挡（只保护读端点等于没保护）
        bean.addUrlPatterns("/*");
        return bean;
    }
}
