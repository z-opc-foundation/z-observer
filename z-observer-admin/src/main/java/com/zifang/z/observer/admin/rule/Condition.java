package com.zifang.z.observer.admin.rule;

/**
 * 阈值条件。
 *
 * <p>比较算子用枚举而不是字符串表达式：阈值规则是「业务方配的、错了会半夜叫人」的东西，
 * 把 {@code ">"} 写成字符串就多了一层能打错的地方，而且错误要到运行期才发现。
 *
 * <p>{@link #GT}/{@link #LT} 对 NaN 一律返回 false。Prometheus 里
 * {@code histogram_quantile} 数据不足时返回 NaN，把它当 0 会凭空造出一次告警。
 */
public enum Condition {

    GT(">") {
        @Override
        public boolean test(double value, double threshold) {
            return !Double.isNaN(value) && value > threshold;
        }
    },

    GTE(">=") {
        @Override
        public boolean test(double value, double threshold) {
            return !Double.isNaN(value) && value >= threshold;
        }
    },

    LT("<") {
        @Override
        public boolean test(double value, double threshold) {
            return !Double.isNaN(value) && value < threshold;
        }
    },

    LTE("<=") {
        @Override
        public boolean test(double value, double threshold) {
            return !Double.isNaN(value) && value <= threshold;
        }
    },

    EQ("==") {
        @Override
        public boolean test(double value, double threshold) {
            return !Double.isNaN(value) && value == threshold;
        }
    },

    NE("!=") {
        @Override
        public boolean test(double value, double threshold) {
            return !Double.isNaN(value) && value != threshold;
        }
    };

    private final String symbol;

    Condition(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public abstract boolean test(double value, double threshold);

    public static Condition fromSymbol(String symbol) {
        for (Condition c : values()) {
            if (c.symbol.equals(symbol)) {
                return c;
            }
        }
        throw new IllegalArgumentException("未知比较算子: " + symbol);
    }
}
