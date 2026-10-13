package com.zifang.z.observer.admin.scrape;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * pull 采集（D1）：去 target 的 {@code /metrics} 拉一次。
 *
 * <p><b>超限返回失败，不截断。</b> Prometheus 在响应体超过 50MB 时会把<b>整份</b>响应丢掉，
 * 而不是截断 —— 截断的 exposition 解析出来是「看起来完整、实际少了一半时序」的数据，
 * 那比报错危险得多。所以这里对齐它的口径：超了就是超了，记为失败，让规则去报 absent。
 *
 * <p>采集失败<b>不抛</b>：一个 target 挂了不该让整轮采集停在这里 —— 那等于
 * 「一个服务挂 → 所有服务的指标都停止更新 → 全体 absent 告警」。
 */
public final class Scraper {

    /** 与 Prometheus 对齐的响应体上限。 */
    public static final int MAX_RESPONSE_BYTES = 50 * 1024 * 1024;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final int timeoutMillis;
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong oversizeCount = new AtomicLong();

    public Scraper() {
        this(5000);
    }

    public Scraper(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** 采一次。失败返回 null 且计数，不抛。 */
    public Result scrape(ScrapeTarget target) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(target.url()).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMillis);
            conn.setReadTimeout(timeoutMillis);
            conn.setRequestProperty("Accept", "text/plain;version=0.0.4");
            int code = conn.getResponseCode();
            if (code != 200) {
                failureCount.incrementAndGet();
                return Result.failed(target, "HTTP " + code);
            }
            byte[] body = readBounded(conn.getInputStream());
            successCount.incrementAndGet();
            return Result.ok(target, new String(body, UTF8));
        } catch (OversizeResponseException e) {
            oversizeCount.incrementAndGet();
            failureCount.incrementAndGet();
            return Result.failed(target, "响应体超过 " + MAX_RESPONSE_BYTES + " 字节，整份丢弃");
        } catch (IOException e) {
            failureCount.incrementAndGet();
            return Result.failed(target, String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_RESPONSE_BYTES) {
                    throw new OversizeResponseException();
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    public long successCount() {
        return successCount.get();
    }

    public long failureCount() {
        return failureCount.get();
    }

    public long oversizeCount() {
        return oversizeCount.get();
    }

    private static final class OversizeResponseException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** 一次采集的结果。失败时 text 为 null、error 有值。 */
    public static final class Result {
        private final ScrapeTarget target;
        private final String text;
        private final String error;

        private Result(ScrapeTarget target, String text, String error) {
            this.target = target;
            this.text = text;
            this.error = error;
        }

        static Result ok(ScrapeTarget target, String text) {
            return new Result(target, text, null);
        }

        static Result failed(ScrapeTarget target, String error) {
            return new Result(target, null, error);
        }

        public ScrapeTarget target() {
            return target;
        }

        public boolean isSuccess() {
            return text != null;
        }

        public String text() {
            return text;
        }

        public String error() {
            return error;
        }
    }
}
