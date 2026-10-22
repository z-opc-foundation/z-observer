package com.zifang.z.observer.admin.notify.zmsg;

import com.zifang.z.observer.admin.notify.AlertNotification;
import com.zifang.z.observer.admin.notify.MessageChannel;
import com.zifang.z.observer.admin.notify.NotifyException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 通知出口走 z-msg 的渠道屏蔽层（跨进程 HTTP）。
 *
 * <p><b>为什么不引 z-msg 的 jar。</b> 需求 009 §1.3 已经把这条红线写死了：
 * z-bot 与 z-msg 只能跨进程 HTTP。而 z-msg-core 又绑死 MyBatis 数据源，
 * 把它拖成收端的硬依赖，收端在没有 ctc 数据源的环境里直接起不来
 * （{@code ObserverAdminConfiguration} 的注释里记着这条教训）。
 * 于是本仓只依赖 {@link MessageChannel} 接口本身，跨进程打 z-msg 的 REST 面。
 *
 * <p><b>为什么走 {@code /api/msg/batch/submit} 而不是直发端点。</b>
 * 需求 009 D2-A 规划的 {@code POST /api/msg/send}（Message 直填）到本卡写作时
 * <b>还没进 z-msg 1.2.2</b>（z-msg 仓现有端点里没有它）。
 * 而 D4 定的分工线是「固定告警走模板审核流，动态内容直填」——
 * 系统告警正是「固定告警」，走模板是对齐 D4 的那一条，
 * 于是用 z-msg 现成的批量提交面：{@code bizType + channel + params + receivers}，
 * 由 z-msg 侧的模板渲染出最终文案。
 *
 * <p><b>凭据只从 z-mist 取。</b> 本类不存任何 token/口令：
 * 收件人（手机号 / 机器人 token / webhook 地址）由 {@link MistCredentialProvider}
 * 按 {@code credentialRef} 运行时取，取到的明文只在内存里过这一条路径。
 */
public final class ZMsgMessageChannel implements MessageChannel {

    private static final Logger LOG = LogManager.getLogger(ZMsgMessageChannel.class);

    private final String baseUrl;
    private final String sendPath;
    private final String channel;
    private final String bizType;
    private final String receiverRef;
    private final String receiverDirect;
    private final MistCredentialProvider credentialProvider;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public ZMsgMessageChannel(String baseUrl, String sendPath, String channel, String bizType,
                              String receiverRef, String receiverDirect,
                              MistCredentialProvider credentialProvider,
                              int connectTimeoutMillis, int readTimeoutMillis) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.sendPath = sendPath == null || sendPath.isEmpty() ? "/api/msg/batch/submit" : sendPath;
        this.channel = channel == null || channel.isEmpty() ? "SMS" : channel;
        this.bizType = bizType == null || bizType.isEmpty() ? "z.observer.alert" : bizType;
        this.receiverRef = receiverRef;
        this.receiverDirect = receiverDirect;
        this.credentialProvider = credentialProvider;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public String name() {
        return "z-msg:" + channel;
    }

    @Override
    public void send(String title, String body) {
        // 兜底路径：只有 title/body 的调用方（例如直接发日志的旧代码）也走得到这里，
        // 于是把它塞进一个最小载荷，不至于整条丢掉。
        AlertNotification minimal = new AlertNotification(
                AlertNotification.Kind.FIRING, "-", "-", "-", "-", "-", "-",
                System.currentTimeMillis(), 0L, title, body, null);
        send(minimal);
    }

    @Override
    public void send(AlertNotification notification) {
        String receiver = resolveReceiver();
        Map<String, String> params = notification.templateParams();
        String json = buildBody(notification, receiver, params);
        String url = baseUrl + sendPath;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMillis);
            conn.setReadTimeout(readTimeoutMillis);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                out.close();
            }
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 200 && code < 300) {
                LOG.info("已提交 z-msg（通道 {}，bizType {}，返回 {}）", channel, bizType, resp);
                return;
            }
            if (isPermanent(code)) {
                // 4xx（除 408/429）：请求本身有问题，重试不会变好 ——
                // 但照样要往上抛，让韧性层落盘待发，绝不静默丢。
                throw new NotifyException("z-msg 拒绝投递 HTTP " + code + ": " + brief(resp),
                        true);
            }
            throw new NotifyException("z-msg 投递失败 HTTP " + code + ": " + brief(resp), false);
        } catch (NotifyException e) {
            throw e;
        } catch (Exception e) {
            // 连不上 / 超时 —— z-msg 不可用，属于可重试的一类
            throw new NotifyException("z-msg 不可达: " + e.getMessage(), false);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 收件人。
     *
     * <p>优先 {@code credentialRef}（z-mist 取明文）；没配 ref 时才用直填的
     * {@code receiver} —— 后者只为本地演练方便，生产必须走 ref，
     * 否则凭据就明文躺在配置文件里了。
     */
    private String resolveReceiver() {
        if (receiverRef != null && !receiverRef.trim().isEmpty()) {
            if (credentialProvider == null) {
                throw new NotifyException("配了凭据引用但没装配 z-mist 取密件器", false);
            }
            return credentialProvider.resolve(receiverRef);
        }
        if (receiverDirect != null && !receiverDirect.trim().isEmpty()) {
            return receiverDirect;
        }
        throw new NotifyException("没配收件人（credential-ref 与 receiver 至少要有一个）", true);
    }

    private String buildBody(AlertNotification notification, String receiver,
                             Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"bizType\":").append(quote(bizType)).append(',');
        sb.append("\"channel\":").append(quote(channel)).append(',');
        sb.append("\"params\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        sb.append("},");
        sb.append("\"receivers\":[{").append("\"receiver\":").append(quote(receiver)).append("}]");
        sb.append('}');
        return sb.toString();
    }

    /** 4xx 里只有 408/429 值得重试；其余是请求本身的问题。 */
    static boolean isPermanent(int code) {
        if (code == 408 || code == 429) {
            return false;
        }
        return code >= 400 && code < 500;
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').trim();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
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
