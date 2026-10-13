package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;

import java.util.concurrent.atomic.DoubleAdder;

/**
 * 单调递增计数器。
 *
 * <p>只允许加，不允许减 —— 不是洁癖：Prometheus 的 counter reset 检测（rate 里
 * 那个 ×3 启发式）建立在"只涨、偶尔因进程重启归零"这个前提上。一旦允许
 * 业务代码把值改小，查询层就无法区分"真的回退了"和"进程重启了"，算出来的
 * rate 会是错的，而且错得没有任何迹象。
 *
 * <p>进程重启归零是正常的，不需要业务代码处理；重置由查询侧识别。
 */
public final class Counter extends Metric {

    private final DoubleAdder value = new DoubleAdder();

    private Counter(String name, String help, Labels labels) {
        super(name, help, labels);
    }

    public static Counter create(String name, String help, Labels labels) {
        return new Counter(name, help, labels);
    }

    public void inc() {
        inc(1d);
    }

    public void inc(double delta) {
        if (delta < 0) {
            // 静默取绝对值会掩盖调用方的语义错误，而调用方往往只是想用 counter 记一个可正可负的量。
            // 那正是 Gauge 的用途，放错工具比报错难查得多。
            throw new IllegalArgumentException(
                    "counter 只能递增，收到 " + delta + "；可增可负的量请用 Gauge");
        }
        value.add(delta);
    }

    @Override
    public double value() {
        return value.sum();
    }
}