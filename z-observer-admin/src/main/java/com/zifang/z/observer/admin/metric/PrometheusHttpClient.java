package com.zifang.z.observer.admin.metric;

import com.zifang.util.json.JsonCursor;
import com.zifang.util.json.JsonUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prometheus HTTP 查询适配（D2 过渡方案 B）。
 *
 * <p>用 {@link HttpURLConnection} 而不是 WebClient/OkHttp：查询面是一次请求一次响应，
 * 没有任何流式或并发复用需求，为此拉一个 HTTP 客户端进来只是多一份依赖面和一份
 * 需要跟着升级的 CVE 清单。
 *
 * <p><b>查询失败抛异常，不返回空列表。</b> 返回空列表会被规则引擎读成「指标不存在」，
 * 于是 Prometheus 自己的一次网络抖动会变成一批实例的 absent 告警 ——
 * 监控系统在最需要它的时候自己坏掉，还顺手喊醒一批人。
 *
 * <p><b>失败必须可见，且失败与「没有这条时序」在结果上彻底分开</b>：
 * <ul>
 *   <li>失败 → 抛 {@link PrometheusQueryException}（消息里带表达式、URL、根因）；</li>
 *   <li>失败 → 记一次失败计数（{@link #failureCount()}）并打一条 WARN 日志；</li>
 *   <li>失败 → 规则引擎计入错误数、<b>告警状态一个字都不动</b>
 *       （不被读成「恢复了」，也不被读成「实例挂了」）；</li>
 *   <li>失败 → 收端自指标 {@code z_observer_rule_eval_errors_total} 上涨，
 *       于是「观测域自己坏了」这件事本身也是可观测的。</li>
 * </ul>
 * 换句话说：<b>查询失败 ≠ 告警静默</b>。静默的只有「Prometheus 说这条时序确实不存在」，
 * 而那是一种<b>业务事实</b>，与「问不动 Prometheus」必须分得清清楚楚。
 */
public final class PrometheusHttpClient implements MetricQuery {

    private static final Logger LOG = LogManager.getLogger(PrometheusHttpClient.class);

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    private final String baseUrl;
    private final int timeoutMillis;

    /** 查询失败累计次数（不含「查不到这条时序」）。 */
    private volatile long failureCount;
    /** 最近一次失败的时间戳（毫秒）；没失败过是 -1。 */
    private volatile long lastFailureMillis = -1L;
    /** 最近一次失败的原因摘要；没失败过是 null。 */
    private volatile String lastFailureMessage;

    public PrometheusHttpClient(String baseUrl) {
        this(baseUrl, 5000);
    }

    public PrometheusHttpClient(String baseUrl, int timeoutMillis) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl 必填");
        }
        String trimmed = baseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public List<Series> instant(String promql, long timeMillis) {
        return parse(get(instantUrl(promql, timeMillis), promql), promql);
    }

    @Override
    public List<Series> range(String promql, long startMillis, long endMillis, long stepMillis) {
        if (startMillis <= 0 || endMillis <= 0 || endMillis < startMillis) {
            throw new IllegalArgumentException("区间非法: " + startMillis + ".." + endMillis);
        }
        if (stepMillis <= 0) {
            throw new IllegalArgumentException("step 必须为正: " + stepMillis);
        }
        String url = baseUrl + "/api/v1/query_range?query=" + enc(promql)
                + "&start=" + (startMillis / 1000L)
                + "&end=" + (endMillis / 1000L)
                + "&step=" + (stepMillis / 1000L);
        return parse(get(url, promql), promql);
    }

    /**
     * 标量查询。
     *
     * <p>走的是即时查询那条路，但按<b>标量</b>结果形态解析：Prometheus 的标量结果是
     * {@code [ts, "值"]} 这个二元组，不带任何标签。
     *
     * <p>表达式写的不是标量时（返回向量 / 矩阵 / 字符串）行为明确：
     * 向量恰好一条 → 取它的值；向量多条 → 抛异常（表达式不合法，不能悄悄取第一条）；
     * 字符串结果 → 抛异常（规则引擎只认数）。
     */
    @Override
    public double scalar(String promql, long timeMillis) {
        JsonCursor data = parseData(get(instantUrl(promql, timeMillis), promql), promql);
        String resultType = text(data, "resultType");
        JsonCursor result = data.path("result");
        if ("scalar".equals(resultType)) {
            if (!result.isArray() || result.size() < 2) {
                throw failure(promql, null, "标量结果形状不合法: " + result, null);
            }
            return readDouble(result);
        }
        if ("string".equals(resultType)) {
            throw failure(promql, null, "查询结果是字符串不是数值", null);
        }
        List<Series> series = parse(data, promql);
        if (series.isEmpty()) {
            return Double.NaN;
        }
        if (series.size() > 1) {
            throw failure(promql, null,
                    "标量查询返回了 " + series.size() + " 条时序，表达式不是标量", null);
        }
        return series.get(0).value();
    }

    @Override
    public long lastSeenMillis(String promql) {
        // 查最近一小时，取每个时序最后一个点的采样时间。
        long end = System.currentTimeMillis();
        long start = end - 3600_000L;
        List<Series> all = range(promql, start, end, 60_000L);
        long newest = -1;
        for (Series s : all) {
            if (s.timestampMillis() > newest) {
                newest = s.timestampMillis();
            }
        }
        return newest;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("URL 编码失败", e);
        }
    }

    private String instantUrl(String promql, long timeMillis) {
        StringBuilder url = new StringBuilder(baseUrl).append("/api/v1/query?query=").append(enc(promql));
        if (timeMillis > 0) {
            url.append("&time=").append(timeMillis / 1000L);
        }
        return url.toString();
    }

    /**
     * 发一次查询请求。
     *
     * <p><b>失败一律走 {@link #failure} 记账 + 打 WARN 日志 + 抛异常</b>，
     * 绝不把失败伪装成空响应 —— 空响应在规则引擎眼里是「这条时序不存在」。
     */
    private byte[] get(String url, String promql) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] body = in == null ? new byte[0] : readAll(in);
            if (code >= 400) {
                throw failure(promql, url, "HTTP " + code + ": " + new String(body, UTF8), null);
            }
            return body;
        } catch (IOException e) {
            // 不可达（连接被拒 / 超时 / DNS 不通）是最常见的一种失败：
            // 「Prometheus 挂了」必须以异常形式冒到规则引擎，而不是变成空结果。
            throw failure(promql, url, "查询请求失败: " + rootMessage(e), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    /**
     * 记一次失败并造出异常。
     *
     * <p>失败要<b>可见</b>：计数器给指标面用，WARN 日志给日志面用，
     * 异常给调用方（规则引擎）用。三条路看到的是同一件事。
     */
    private PrometheusQueryException failure(String promql, String url, String reason, Throwable cause) {
        failureCount++;
        lastFailureMillis = System.currentTimeMillis();
        lastFailureMessage = reason;
        String message = "Prometheus 查询失败: " + reason
                + " [promql=" + promql
                + (url == null ? "" : ", url=" + url) + "]";
        LOG.warn(message, cause);
        return cause == null
                ? new PrometheusQueryException(message)
                : new PrometheusQueryException(message, cause);
    }

    /** 查询失败累计次数（不含「查不到这条时序」）。 */
    public long failureCount() {
        return failureCount;
    }

    /** 最近一次失败的时间戳（毫秒）；没失败过是 -1。 */
    public long lastFailureMillis() {
        return lastFailureMillis;
    }

    /** 最近一次失败的原因摘要；没失败过是 null。 */
    public String lastFailureMessage() {
        return lastFailureMessage;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY_BYTES) {
                    throw new IOException("响应体超过上限 " + MAX_BODY_BYTES);
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * 解析响应（包级可见，便于同包测试直接喂响应体）。
     *
     * @see #parse(byte[], String)
     */
    List<Series> parse(byte[] body) {
        return parse(body, null);
    }

    /**
     * 解析响应。
     *
     * <p>区间查询的每个元素是 {@code values:[{ts,v}]}，即时查询是 {@code value:{ts,v}}，
     * 两者都摊平成同一个 {@link Series} 列表 —— 区间结果在调用侧按时间升序用即可。
     * 返回空列表是合法结果（没有匹配的时序），与请求失败严格区分。
     *
     * <p>按 {@code resultType} 分流，避免把<b>标量</b>结果当成「没有时序」：
     * 标量结果是 {@code [ts, "值"]} 这个二元组，不是时序数组；
     * 当成时序数组解析会静默得到空列表 —— 那是把「查到了一个数」说成「什么都没有」。
     */
    List<Series> parse(byte[] body, String promql) {
        return parse(parseData(body, promql), promql);
    }

    /** 取出 {@code data} 段；状态不是 success 一律当失败记账并抛异常。 */
    private JsonCursor parseData(byte[] body, String promql) {
        try {
            JsonCursor root = JsonCursor.of(JsonUtil.parse(new String(body, UTF8)));
            String status = text(root, "status");
            if (!"success".equals(status)) {
                throw failure(promql, null, "查询未成功: status=" + status
                        + " " + text(root, "errorType") + " " + text(root, "error"), null);
            }
            JsonCursor data = root.path("data");
            if (data.isMissing()) {
                // status=success 却没有 data 段 = 响应不是我们认识的形状。
                // 当成「没有时序」会把一次协议错读成业务事实，必须当失败。
                throw failure(promql, null, "响应里没有 data 段", null);
            }
            return data;
        } catch (PrometheusQueryException e) {
            throw e;
        } catch (RuntimeException e) {
            // JsonUtil 抛的是非受检异常，签名不再是 IOException
            throw failure(promql, null, "响应不是合法 JSON", e);
        }
    }

    /** 把 {@code data} 段摊平成 {@link Series} 列表。 */
    private List<Series> parse(JsonCursor data, String promql) {
        if (data == null || data.isMissing()) {
            return Collections.emptyList();
        }
        String resultType = text(data, "resultType");
        JsonCursor result = data.path("result");
        if ("string".equals(resultType)) {
            throw failure(promql, null, "查询结果是字符串不是数值", null);
        }
        if ("scalar".equals(resultType)) {
            // 标量没有标签，包成一条无标签时序，值就是标量本身
            if (!result.isArray() || result.size() < 2) {
                throw failure(promql, null, "标量结果形状不合法: " + result, null);
            }
            return Collections.singletonList(new Series(
                    Collections.<String, String>emptyMap(), readDouble(result), readTs(result) * 1000L));
        }
        if (!result.isArray()) {
            return Collections.emptyList();
        }
        List<Series> out = new ArrayList<Series>();
        for (JsonCursor item : result.elements()) {
            Map<String, String> labels = readLabels(item.path("metric"));
            JsonCursor value = item.path("value");
            if (!value.isMissing()) {
                out.add(new Series(labels, readDouble(value), readTs(value) * 1000L));
                continue;
            }
            JsonCursor values = item.path("values");
            if (values.isArray()) {
                for (JsonCursor point : values.elements()) {
                    out.add(new Series(labels, readDouble(point), readTs(point) * 1000L));
                }
            }
        }
        return out;
    }

    private static String text(JsonCursor node, String field) {
        JsonCursor v = node == null ? JsonCursor.missing() : node.path(field);
        return v.isMissing() || v.isNull() ? null : v.asText();
    }

    private static Map<String, String> readLabels(JsonCursor metricNode) {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        if (metricNode == null || !metricNode.isObject()) {
            return labels;
        }
        // JsonCursor.fieldNames() 返回 Set 而不是 Iterator，这里按名回取
        Set<String> names = metricNode.fieldNames();
        for (String name : names) {
            labels.put(name, metricNode.path(name).asText());
        }
        return labels;
    }

    /**
     * 取采样值。Prometheus 的点是 {@code [unix_ts, "value"]} 数组，不是对象。
     *
     * <p>值一律是 JSON 字符串，且可能是 "NaN" / "+Inf" / "-Inf"。这三个必须如实解析：
     * histogram_quantile 在数据不足时返回 NaN，把 NaN 当 0 会让「分位数算不出来」
     * 变成「分位数是 0」，阈值规则的表现完全不同。
     */
    private static double readDouble(JsonCursor point) {
        JsonCursor v = point.size() > 1 ? point.at(1) : JsonCursor.missing();
        if (v.isMissing() || v.isNull()) {
            return Double.NaN;
        }
        String raw = v.asText();
        if ("NaN".equals(raw)) {
            return Double.NaN;
        }
        if ("+Inf".equals(raw) || "Inf".equals(raw)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-Inf".equals(raw)) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static long readTs(JsonCursor point) {
        JsonCursor v = point.size() > 0 ? point.at(0) : JsonCursor.missing();
        if (v.isMissing() || v.isNull()) {
            return 0L;
        }
        // JsonCursor 没有 isNumber()，按底层值的类型判（等价于 Jackson 的 JsonNode.isNumber）
        if (v.value() instanceof Number) {
            return ((Number) v.value()).longValue();
        }
        try {
            return (long) Double.parseDouble(v.asText());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
