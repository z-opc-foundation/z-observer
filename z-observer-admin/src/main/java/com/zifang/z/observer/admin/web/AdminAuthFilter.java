package com.zifang.z.observer.admin.web;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理面的入站鉴权闸门（bearer 令牌）。
 *
 * <p><b>这道闸门补的是什么洞。</b> 收端的管理 HTTP 面（8080，NodePort 31891）
 * 在加这道闸门之前**零鉴权**：模块里没有 security 依赖、没有 SSO、没有 Filter/Interceptor
 * ⇒ 任何人能连上就能读告警、日志、链路、规则，
 * <b>还能写</b>：{@code POST /api/rules}（同 id 覆盖规则）、{@code POST /api/silences}（加静默）、
 * {@code POST /api/inhibits}（加抑制）—— 这三个是「把监控关掉」的原语。
 * ⇒ <b>只保护读端点等于没保护</b>：本过滤器挂在整条 servlet 链上，
 * 读面和写面一起挡。
 *
 * <p><b>失败一律 401 + {@code WWW-Authenticate}</b>（RFC 6750）：
 * 挑战里写明 realm，客户端据此知道该带什么来。
 *
 * <p><b>常数时间比较</b>（{@link MessageDigest#isEqual}）：按字节短路比较会留下时序侧信道。
 *
 * <p><b>为什么没有 z-mist 取数</b>：密钥在启动时取一次就固定住（见 {@code SecretSource}），
 * 每请求去取一次既慢又让「取不到密钥」这件事从启动期挪到运行期。
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    /** 挑战的 realm。 */
    public static final String REALM = "z-observer-admin";

    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] token;

    private final List<String> excludedPaths;

    /**
     * @param token         期望的 bearer 令牌（明文，**只留在内存里**）。
     * @param excludedPaths 免鉴权的路径前缀。
     */
    public AdminAuthFilter(String token, List<String> excludedPaths) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("令牌必填");
        }
        this.token = token.trim().getBytes(StandardCharsets.UTF_8);
        this.excludedPaths = new ArrayList<String>();
        if (excludedPaths != null) {
            for (String p : excludedPaths) {
                if (p != null && !p.trim().isEmpty()) {
                    this.excludedPaths.add(p.trim());
                }
            }
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isExcluded(path)) {
            chain.doFilter(request, response);
            return;
        }
        String presented = bearerOf(request.getHeader("Authorization"));
        if (presented != null && MessageDigest.isEqual(
                token, presented.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        unauthorized(request, response);
    }

    private boolean isExcluded(String path) {
        if (path == null) {
            return false;
        }
        for (String p : excludedPaths) {
            if (path.equals(p) || path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private void unauthorized(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"" + REALM + "\"");
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        // ⚠ **不把收到的头原样回显**：回显等于把凭据写进日志与响应体。
        response.getWriter().write(
                "{\"error\":\"unauthorized\",\"message\":\"需要 Authorization: Bearer <token>\"}");
    }

    /** 取 bearer 令牌；头缺失或格式不对 ⇒ null。 */
    static String bearerOf(String authorization) {
        if (authorization == null) {
            return null;
        }
        String a = authorization.trim();
        if (a.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!a.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String v = a.substring(BEARER_PREFIX.length()).trim();
        return v.isEmpty() ? null : v;
    }
}
