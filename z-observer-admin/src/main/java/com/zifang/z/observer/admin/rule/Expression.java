package com.zifang.z.observer.admin.rule;

/** 规则表达式类型。 */
public enum Expression {

    /** 阈值：查询有数值且满足比较条件。 */
    THRESHOLD,

    /**
     * 缺席：查询<b>没有</b>任何数据。
     *
     * <p>这是「进程挂了」的唯一可靠判据。写成 {@code up == 0} 是错的：
     * {@code up} 自己也是一条时序，进程彻底消失后 Prometheus 会因为 staleness
     * 把它从查询结果里删掉，表达式返回空而不是 0，于是「up == 0」永远不成立 ——
     * 恰恰在最需要它的那次挂掉时失效。
     */
    ABSENT,

    /** 组合布尔：多个子条件的与。 */
    BOOLEAN
}
