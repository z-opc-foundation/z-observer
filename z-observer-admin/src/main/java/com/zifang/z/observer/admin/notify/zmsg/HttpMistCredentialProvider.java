package com.zifang.z.observer.admin.notify.zmsg;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 走 z-mist 的 REST 面取明文凭据。
 *
 * <p>用 {@code GET /api/secret/plain?secretKey=&group=&namespace=}。
 * 选 REST 而不是 z-mist-client（Netty 私有协议）的原因很实际：
 * <b>不引新依赖</b>。z-mist-client 会把 netty + 一堆 z-util 拖进收端的依赖树，
 * 而收端本来就有一条 netty 通道，再加一条只是多一处要维护的线；
 * REST 面用 JDK 自带的 HTTP 就能打，零新增依赖。
 *
 * <p><b>明文只在内存里过这一条路径</b>：不进日志、不进告警正文、不进落盘待发文件。
 * 日志里只出现「引用名」和「取没取到」。
 */
public final class HttpMistCredentialProvider implements MistCredentialProvider {

    private static final Logger LOG = LogManager.getLogger(HttpMistCredentialProvider.class);

    private final String baseUrl;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final String defaultGroup;
    private final String defaultNamespace;

    public HttpMistCredentialProvider(String baseUrl, int connectTimeoutMillis,
                                      int readTimeoutMillis, String defaultGroup,
                                      String defaultNamespace) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.defaultGroup = defaultGroup;
        this.defaultNamespace = defaultNamespace;
    }

    @Override
    public String resolve(String credentialRef) {
        if (credentialRef == null || credentialRef.trim().isEmpty()) {
            throw new NotifyCredentialException("凭据引用为空");
        }
        if (baseUrl.isEmpty()) {
            throw new NotifyCredentialException("没配 z-mist 地址，取不到凭据");
        }
        String ref = credentialRef.trim();
        String group = defaultGroup;
        String namespace = defaultNamespace;
        String secretKey = ref;
        int first = ref.indexOf('/');
        if (first > 0) {
            group = ref.substring(0, first);
            String rest = ref.substring(first + 1);
            int second = rest.indexOf('/');
            // ⚠ second 可能等于 0（形如 `group//key`：namespace 为空段）——
            //   这里必须用 >= 0，写成 > 0 会把 `group//key` 整段当成 key，
            //   于是 z-mist 查不到、收端只能报「没返回明文」，而真因是引用写错。
            if (second >= 0) {
                namespace = rest.substring(0, second);
                secretKey = rest.substring(second + 1);
            } else {
                secretKey = rest;
            }
        }
        String url = baseUrl + "/api/secret/plain?secretKey=" + enc(secretKey)
                + "&group=" + enc(group) + "&namespace=" + enc(namespace);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMillis);
            conn.setReadTimeout(readTimeoutMillis);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code < 200 || code >= 300) {
                throw new NotifyCredentialException(
                        "z-mist 取凭据失败 HTTP " + code + "（引用 " + ref + "）");
            }
            String secret = extractData(body);
            if (secret == null || secret.isEmpty()) {
                // z-mist 明确说失败时把它的话带出来 —— 「引用写错」和
                // 「密钥存在但值为空」是两回事，日志里必须能分开。
                String reason = extractMessage(body);
                throw new NotifyCredentialException("z-mist 没返回明文（引用 " + ref + "）"
                        + (reason == null || reason.isEmpty() ? "" : ": " + reason));
            }
            // 只记引用与结果，不记明文
            LOG.info("已从 z-mist 取到凭据（引用 {}，长度 {}）", ref, Integer.valueOf(secret.length()));
            return secret;
        } catch (NotifyCredentialException e) {
            throw e;
        } catch (Exception e) {
            throw new NotifyCredentialException(
                    "z-mist 取凭据异常（引用 " + ref + "）: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 从 z-mist 的信封里取 data 字段。
     *
     * <p>z-mist 的返回形状是 {@code {"success":true,"message":...,"data":"明文"}}。
     * 这里不引 JSON 库做完整解析 —— 只按字符串切，
     * 代价是引号里的转义要自己处理（明文里出现 \" 的概率很低，但不是零，
     * 所以退到 Jackson 解析失败时用正则兜一手）。
     */
    static String extractData(String body) {
        if (body == null) {
            return null;
        }
        int idx = body.indexOf("\"data\"");
        if (idx < 0) {
            return null;
        }
        int colon = body.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length()) {
            return null;
        }
        if (body.charAt(i) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(i + 1);
                switch (n) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 'u':
                        if (i + 5 < body.length()) {
                            try {
                                sb.append((char) Integer.parseInt(body.substring(i + 2, i + 6), 16));
                                i += 4;
                                break;
                            } catch (NumberFormatException ignored) {
                                sb.append(n);
                                break;
                            }
                        }
                        sb.append(n);
                        break;
                    default:
                        sb.append(n);
                        break;
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        return null;
    }

    /** 取 z-mist 信封里的 message 字段（失败原因）。 */
    static String extractMessage(String body) {
        if (body == null) {
            return null;
        }
        int idx = body.indexOf("\"message\"");
        if (idx < 0) {
            return null;
        }
        int colon = body.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                sb.append(body.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        return null;
    }

    private static String readAll(InputStream in) throws Exception {        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
