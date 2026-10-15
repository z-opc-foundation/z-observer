package com.zifang.z.observer.admin.metric;

import com.zifang.util.json.JsonCursor;
import com.zifang.util.json.JsonUtil;

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
 */
public final class PrometheusHttpClient implements MetricQuery {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    private final String baseUrl;
    private final int timeoutMillis;

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
        StringBuilder url = new StringBuilder(baseUrl).append("/api/v1/query?query=").append(enc(promql));
        if (timeMillis > 0) {
            url.append("&time=").append(timeMillis / 1000L);
        }
        return parse(get(url.toString()));
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
        return parse(get(url));
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

    private byte[] get(String url) {
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
                throw new PrometheusQueryException("查询失败 HTTP " + code + ": "
                        + new String(body, UTF8));
            }
            return body;
        } catch (IOException e) {
            throw new PrometheusQueryException("查询请求失败: " + url, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
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
     * 解析响应。
     *
     * <p>区间查询的每个元素是 {@code values:[{ts,v}]}，即时查询是 {@code value:{ts,v}}，
     * 两者都摊平成同一个 {@link Series} 列表 —— 区间结果在调用侧按时间升序用即可。
     * 返回空列表是合法结果（没有匹配的时序），与请求失败严格区分。
     */
    List<Series> parse(byte[] body) {
        try {
            JsonCursor root = JsonCursor.of(JsonUtil.parse(new String(body, UTF8)));
            String status = text(root, "status");
            if (!"success".equals(status)) {
                throw new PrometheusQueryException("查询未成功: status=" + status
                        + " " + text(root, "errorType") + " " + text(root, "error"));
            }
            JsonCursor data = root.path("data");
            if (data.isMissing()) {
                return Collections.emptyList();
            }
            JsonCursor result = data.path("result");
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
        } catch (PrometheusQueryException e) {
            throw e;
        } catch (RuntimeException e) {
            // JsonUtil 抛的是非受检异常，签名不再是 IOException
            throw new PrometheusQueryException("响应不是合法 JSON", e);
        }
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
