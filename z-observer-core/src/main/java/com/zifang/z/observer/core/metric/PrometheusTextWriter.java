package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * 把注册表渲染成 Prometheus 文本格式。
 *
 * <p>本项目不引 Prometheus 依赖，但输出必须落在它的文本格式上 ——
 * 时序存储既然交给 Prometheus，就不能自造一种它读不懂的格式；文本格式也是唯一
 * 人能直接 curl 出来看懂的格式，排障时能少一道解码。
 *
 * <p>格式（{@code https://prometheus.io/docs/instrumenting/exposition_formats/}）：
 * <pre>
 * # HELP http_requests_total 说明
 * # TYPE http_requests_total counter
 * http_requests_total{method="GET",code="200"} 3
 * </pre>
 */
public final class PrometheusTextWriter {

    private final MetricRegistry registry;

    public PrometheusTextWriter(MetricRegistry registry) {
        this.registry = registry;
    }

    public String render() {
        StringBuilder sb = new StringBuilder(4096);
        write(sb);
        return sb.toString();
    }

    public void write(Writer out) throws IOException {
        StringBuilder sb = new StringBuilder(8192);
        write(sb);
        out.write(sb.toString());
        out.flush();
    }

    private void write(StringBuilder sb) {
        // 观测系统自身的健康指标放在最前：收不到应用指标时，先看它自己还活着没有。
        sb.append("# HELP z_observer_series_rejected_total 因为基数超限被拒绝的时序注册次数\n");
        sb.append("# TYPE z_observer_series_rejected_total counter\n");
        sb.append("z_observer_series_rejected_total ")
                .append(registry.rejectedSeriesCount()).append('\n');

        for (MetricFamily family : registry.families()) {
            if (family.help() != null && !family.help().isEmpty()) {
                sb.append("# HELP ").append(family.name()).append(' ')
                        .append(escapeHelp(family.help())).append('\n');
            }
            sb.append("# TYPE ").append(family.name()).append(' ')
                    .append(family.kind().prometheusName()).append('\n');
            for (Metric m : family.series()) {
                switch (family.kind()) {
                    case HISTOGRAM:
                        writeHistogram(sb, (Histogram) m);
                        break;
                    default:
                        writeSample(sb, family.name(), m.labels(), null, m.value());
                        break;
                }
            }
        }
    }

    private void writeHistogram(StringBuilder sb, Histogram h) {
        double[] bounds = h.bounds();
        long[] counts = h.cumulativeBucketCounts();
        for (int i = 0; i < bounds.length; i++) {
            writeSample(sb, h.name() + "_bucket", h.labels(), formatBound(bounds[i]), counts[i]);
        }
        // +Inf 桶是必需的：没有它，超过最大桶上界的样本就凭空消失，
        // 而 sum/count 仍然反映总数，两边对不上而查询侧看不出原因。
        writeSample(sb, h.name() + "_bucket", h.labels(), "+Inf", h.overflowCount());
        writeSample(sb, h.name() + "_sum", h.labels(), null, h.totalSum());
        writeSample(sb, h.name() + "_count", h.labels(), null, h.totalCount());
    }

    private void writeSample(StringBuilder sb, String name, Labels labels,
                             String extraLabel, double value) {
        sb.append(name);
        boolean needBrace = !labels.isEmpty() || extraLabel != null;
        if (needBrace) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, String> e : labels.asMap().entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append("=\"").append(escapeLabelValue(e.getValue())).append('"');
            }
            if (extraLabel != null) {
                if (!first) {
                    sb.append(',');
                }
                // le 是保留名，业务侧已被 MetricRegistry 拦住，这里补上去不会撞名。
                sb.append("le=\"").append(extraLabel).append('"');
            }
            sb.append('}');
        }
        sb.append(' ').append(formatValue(value)).append('\n');
    }

    /** le 的取值必须是能还原成数字的字面量：+Inf 是唯一允许的非数字形态。 */
    private static String formatBound(double b) {
        return formatValue(b);
    }

    static String formatValue(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (v == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            // 整数值去掉小数点：时序存储里整数的编码方式和小数不同，
            // 让 3 写成 3.0 会白白多占字节，也让文本 diff 全是噪声。
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    static String escapeLabelValue(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            // 文本格式只定义了这三种转义。漏掉反斜杠会让后面的转义序列被二次解释，
            // 漏掉引号会让标签在解析处提前闭合、把后续标签吞进值里。
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static String escapeHelp(String v) {
        if (v == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}