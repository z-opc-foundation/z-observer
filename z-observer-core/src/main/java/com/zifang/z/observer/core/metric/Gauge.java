package com.zifang.z.observer.core.metric;

import com.zifang.z.observer.core.model.Labels;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 可增可减的瞬时值。
 *
 * <p>用于"此刻是多少"而非"一共发生多少次"：连接池占用、队列深度、线程数、
 * 某个业务对象当前持有的金额。把它和 Counter 混用是最常见的观测事故 ——
 * 用 Gauge 记一个只涨的量，查询侧按 counter 算rate会算出一个随值上下抖的假速率；
 * 反过来用 Counter 记一个会回落的量，重启检测会把回落误判成重置，凭空造出一个尖峰。
 *
 * <p>用 AtomicLong 存 double 的位模式而不是 Double：Double 字段要 volatile 才有
 * 跨线程可见性，而 volatile double 在 64 位 JVM 上并非原子的读写实现。
 */
public final class Gauge extends Metric {

    private final AtomicLong bits;

    private Gauge(String name, String help, Labels labels) {
        super(name, help, labels);
        this.bits = new AtomicLong(Double.doubleToRawLongBits(0d));
    }

    public static Gauge create(String name, String help, Labels labels) {
        return new Gauge(name, help, labels);
    }

    public void set(double v) {
        bits.set(Double.doubleToRawLongBits(v));
    }

    public void inc() {
        add(1d);
    }

    public void dec() {
        add(-1d);
    }

    public void add(double delta) {
        // CAS 循环而不是 set(sum+delta)：多个线程同时增减是队列深度这类指标的常态，
        // 非原子读改写会把中间的两次 inc 吃掉一次。
        long prev, next;
        do {
            prev = bits.get();
            next = Double.doubleToRawLongBits(Double.longBitsToDouble(prev) + delta);
        } while (!bits.compareAndSet(prev, next));
    }

    @Override
    public double value() {
        return Double.longBitsToDouble(bits.get());
    }
}