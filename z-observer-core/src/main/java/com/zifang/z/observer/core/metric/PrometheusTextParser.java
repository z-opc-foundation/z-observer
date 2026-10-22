package com.zifang.z.observer.core.metric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus 文本格式（text format 0.0.4）的<b>读取侧</b>。
 *
 * <p>本仓有写侧（{@link PrometheusTextWriter}，产出 exposition），采集层必须有读侧
 * —— 否则「抓到了一坨文本」和「抓到了一组可用的时序」是同一件事，而后者才是规则能用的东西。
 *
 * <p><b>解析失败不抛异常，而是逐行记账。</b> 一个 target 的 exposition 里混进一行坏数据，
 * 整份丢掉等于把另外几十条好时序一起扔了（一次抖动 ⇒ 一批 absent 告警）；
 * 全盘接受又等于把坏数据当真的（解析出来的时序不可信）。折中办法是
 * <b>好数据照收、坏数据记账</b>：调用方拿到 {@link ParseResult#errors()} 就能说清
 * 「这一轮有几行没读进来」，而不是只看到一个空列表。
 *
 * <p>支持范围（0.0.4 文本格式全集的常用子集）：
 * <ul>
 *   <li>{@code # HELP <name> <help>} / {@code # TYPE <name> <type>} 元信息行；</li>
 *   <li>样本行 {@code name{label="value",...} value [timestamp]}；</li>
 *   <li>标签值转义 {@code \\ \" \n}（与写侧 {@code escapeLabelValue} 严格对称）；</li>
 *   <li>特殊值 {@code NaN} / {@code +Inf} / {@code -Inf}；</li>
 *   <li>可选的毫秒时间戳（缺省视为「采集时刻」，由调用方补）。</li>
 * </ul>
 * 不支持也不打算支持：{@code OpenMetrics} 的 {@code _created} / {@code exemplars}、
 * 非法的 {@code NaN} 之外的字面量（如 {@code 1_000}）。
 */
public final class PrometheusTextParser {

    /** 一条解析出来的样本。 */
    public static final class ParsedSample {

        private final String name;
        private final Map<String, String> labels;
        private final double value;
        private final Long timestampMillis;
        private final String type;
        private final String help;

        ParsedSample(String name, Map<String, String> labels, double value,
                     Long timestampMillis, String type, String help) {
            this.name = name;
            this.labels = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(labels));
            this.value = value;
            this.timestampMillis = timestampMillis;
            this.type = type;
            this.help = help;
        }

        public String name() {
            return name;
        }

        public Map<String, String> labels() {
            return labels;
        }

        public double value() {
            return value;
        }

        /** 样本自带的时间戳；exposition 里没写就是 null（由调用方补采集时刻）。 */
        public Long timestampMillis() {
            return timestampMillis;
        }

        /** {@code # TYPE} 声明的类型；没声明就是 null。 */
        public String type() {
            return type;
        }

        /** {@code # HELP} 声明的说明；没声明就是 null。 */
        public String help() {
            return help;
        }

        /** 时序身份：指标名 + 标签集（与 {@code MetricSample#seriesKey()} 同口径）。 */
        public String seriesKey() {
            if (labels.isEmpty()) {
                return name;
            }
            StringBuilder sb = new StringBuilder(name).append('{');
            boolean first = true;
            for (Map.Entry<String, String> e : labels.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.append('}').toString();
        }

        @Override
        public String toString() {
            return seriesKey() + " " + value;
        }
    }

    /** 一处解析错误：哪一行、什么内容、为什么读不进来。 */
    public static final class ParseError {

        private final int lineNumber;
        private final String line;
        private final String reason;

        ParseError(int lineNumber, String line, String reason) {
            this.lineNumber = lineNumber;
            this.line = line;
            this.reason = reason;
        }

        public int lineNumber() {
            return lineNumber;
        }

        public String line() {
            return line;
        }

        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return "第 " + lineNumber + " 行解析失败（" + reason + "）: " + line;
        }
    }

    /** 一次解析的结果：好样本 + 坏行。 */
    public static final class ParseResult {

        private final List<ParsedSample> samples;
        private final List<ParseError> errors;

        ParseResult(List<ParsedSample> samples, List<ParseError> errors) {
            this.samples = Collections.unmodifiableList(samples);
            this.errors = Collections.unmodifiableList(errors);
        }

        public List<ParsedSample> samples() {
            return samples;
        }

        public List<ParseError> errors() {
            return errors;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public int sampleCount() {
            return samples.size();
        }

        public int errorCount() {
            return errors.size();
        }

        @Override
        public String toString() {
            return "样本 " + samples.size() + " 条，坏行 " + errors.size() + " 行";
        }
    }

    /**
     * 解析一段 exposition。
     *
     * @param text exposition 文本；null 当作空文本（不抛异常）
     * @return 解析结果（永不抛异常）
     */
    public ParseResult parse(String text) {
        List<ParsedSample> samples = new ArrayList<ParsedSample>();
        List<ParseError> errors = new ArrayList<ParseError>();
        if (text == null || text.isEmpty()) {
            return new ParseResult(samples, errors);
        }
        Map<String, String> types = new LinkedHashMap<String, String>();
        Map<String, String> helps = new LinkedHashMap<String, String>();

        String[] lines = text.split("\r\n|\n|\r", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.charAt(0) == '#') {
                readMeta(line, types, helps);
                continue;
            }
            try {
                samples.add(readSample(line, types, helps));
            } catch (RuntimeException e) {
                errors.add(new ParseError(lineNumber, raw, String.valueOf(e.getMessage())));
            }
        }
        return new ParseResult(samples, errors);
    }

    /** 读 {@code # HELP} / {@code # TYPE} 元信息行；不认识的注释直接忽略。 */
    private static void readMeta(String line, Map<String, String> types, Map<String, String> helps) {
        if (line.startsWith("# HELP ")) {
            String rest = line.substring("# HELP ".length()).trim();
            int sp = rest.indexOf(' ');
            if (sp <= 0) {
                return;
            }
            helps.put(rest.substring(0, sp), rest.substring(sp + 1));
        } else if (line.startsWith("# TYPE ")) {
            String rest = line.substring("# TYPE ".length()).trim();
            int sp = rest.indexOf(' ');
            if (sp <= 0) {
                return;
            }
            types.put(rest.substring(0, sp), rest.substring(sp + 1).trim());
        }
    }

    private static ParsedSample readSample(String line, Map<String, String> types,
                                            Map<String, String> helps) {
        int brace = line.indexOf('{');
        String name;
        Map<String, String> labels = new LinkedHashMap<String, String>();
        String rest;
        if (brace < 0) {
            int sp = line.indexOf(' ');
            if (sp <= 0) {
                throw new IllegalArgumentException("样本行没有指标名或取值");
            }
            name = line.substring(0, sp);
            rest = line.substring(sp + 1).trim();
        } else {
            name = line.substring(0, brace).trim();
            int close = findLabelEnd(line, brace);
            if (close < 0) {
                throw new IllegalArgumentException("标签区没有闭合的 '}'");
            }
            readLabels(line.substring(brace + 1, close), labels);
            rest = line.substring(close + 1).trim();
        }
        if (name.isEmpty()) {
            throw new IllegalArgumentException("样本行没有指标名");
        }
        String[] parts = rest.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("样本行没有取值");
        }
        double value = parseValue(parts[0]);
        Long timestamp = null;
        if (parts.length > 1) {
            try {
                timestamp = Long.valueOf(parts[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("时间戳不是整数: " + parts[1]);
            }
        }
        return new ParsedSample(name, labels, value, timestamp,
                types.get(name), helps.get(name));
    }

    /** 找标签区的闭合 {@code }}：跳过引号里的 {@code }}，并处理反斜杠转义。 */
    private static int findLabelEnd(String line, int from) {
        boolean inQuotes = false;
        for (int i = from + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && inQuotes) {
                i++;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '}' && !inQuotes) {
                return i;
            }
        }
        return -1;
    }

    private static void readLabels(String body, Map<String, String> labels) {
        if (body.trim().isEmpty()) {
            return;
        }
        int i = 0;
        int n = body.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == ',')) {
                i++;
            }
            if (i >= n) {
                return;
            }
            int nameStart = i;
            while (i < n && body.charAt(i) != '=' && body.charAt(i) != ',') {
                i++;
            }
            if (i >= n || body.charAt(i) != '=') {
                throw new IllegalArgumentException("标签缺少 '='");
            }
            String labelName = body.substring(nameStart, i).trim();
            i++; // '='
            while (i < n && Character.isWhitespace(body.charAt(i))) {
                i++;
            }
            if (i >= n || body.charAt(i) != '"') {
                throw new IllegalArgumentException("标签值必须用双引号包住: " + labelName);
            }
            i++; // 开引号
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (i < n) {
                char c = body.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    char next = body.charAt(i + 1);
                    if (next == 'n') {
                        value.append('\n');
                    } else if (next == '\\') {
                        value.append('\\');
                    } else if (next == '"') {
                        value.append('"');
                    } else {
                        // 文本格式只定义了这三种转义；别的原样保留，别把数据悄悄改掉。
                        value.append('\\').append(next);
                    }
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    closed = true;
                    i++;
                    break;
                }
                value.append(c);
                i++;
            }
            if (!closed) {
                throw new IllegalArgumentException("标签值没有闭合的 '\"': " + labelName);
            }
            if (labelName.isEmpty()) {
                throw new IllegalArgumentException("标签名不能为空");
            }
            labels.put(labelName, value.toString());
        }
    }

    private static double parseValue(String token) {
        if ("NaN".equals(token)) {
            return Double.NaN;
        }
        if ("+Inf".equals(token)) {
            return Double.POSITIVE_INFINITY;
        }
        if ("-Inf".equals(token)) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("取值不是数字: " + token);
        }
    }
}
