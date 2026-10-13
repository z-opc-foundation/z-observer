package com.zifang.z.observer.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一组标签。
 *
 * <p>标签是指标的「维度」，也是本系统最容易翻车的地方：维度基数（distinct label set 的数量）
 * 随维度个数呈乘法增长。加一个像 userId 这样的维度就能把一条时序的存储量放大上万倍，
 * 而这种改动看起来只是「多埋了一个字段」。所以：
 *
 * <ul>
 *   <li>标签名走白名单，新增维度必须先注册（见 {@code z.observer.metrics.allowed-dimensions}）；</li>
 *   <li>白名单外的维度在这里就被丢弃，而不是原样上报 —— 丢在发端比丢在存储层便宜得多。</li>
 * </ul>
 *
 * <p>值不做白名单：值就是基数爆炸的来源（比如把订单号当值），但值本身无法预先枚举。
 * 所以值侧靠基数限流（{@code MaxSeriesPerMetric} 那一类）兜底，不靠这里拦。
 */
public final class Labels {

    private static final Labels EMPTY = new Labels(Collections.<String, String>emptyMap());

    private final Map<String, String> values;

    private Labels(Map<String, String> values) {
        this.values = values;
    }

    public static Labels empty() {
        return EMPTY;
    }

    /** 保留标签顺序，便于序列化结果稳定、便于测试断言。 */
    public static Builder builder() {
        return new Builder();
    }

    public Map<String, String> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * 该 series 的身份串，用作存储层的排序键与去重键。
     *
     * <p>维度按名排序后再拼：调用方注册标签的顺序可能不同，但同一组标签必须算出同一个 key，
     * 否则同一条时序会被存成两条、指标被计两遍。这类错不会报错，只会让数字悄悄翻倍。
     */
    public String seriesKey() {
        if (values.isEmpty()) {
            return "";
        }
        String[] names = values.keySet().toArray(new String[0]);
        java.util.Arrays.sort(names);
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            // 逗号/等号/转义符出现在标签值里会让 seriesKey 歧义（两个不同的标签集算出同一个串），
            // 那属于静默的数据串档，所以在这里转义而不是指望调用方不传这些字符。
            sb.append(name).append('=').append(escape(values.get(name)));
        }
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('\\') < 0 && value.indexOf(',') < 0 && value.indexOf('=') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == ',' || c == '=') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Labels" + values;
    }

    public static final class Builder {

        private final Map<String, String> values = new LinkedHashMap<String, String>();

        /** 跳过 null 名的维度：埋点处常见「这个维度我这儿没有」而不是「这个维度是 null」。 */
        public Builder put(String name, String value) {
            if (name != null && !name.isEmpty() && value != null) {
                values.put(name, value);
            }
            return this;
        }

        public Labels build() {
            if (values.isEmpty()) {
                return EMPTY;
            }
            return new Labels(Collections.unmodifiableMap(new LinkedHashMap<String, String>(values)));
        }
    }
}