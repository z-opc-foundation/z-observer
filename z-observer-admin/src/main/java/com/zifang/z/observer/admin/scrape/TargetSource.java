package com.zifang.z.observer.admin.scrape;

import java.util.List;

/**
 * 采集目标发现源。
 *
 * <p>抽成接口而不是直接把 z-config naming 写死在收端里：M1 允许「暂时只跑本地几个
 * target」，接上 z-config 之后换实现即可，采集循环与规则引擎一行不改。
 */
public interface TargetSource {

    /** 源的名字，进日志与自指标，便于回答「这一轮 target 是从哪来的」。 */
    String name();

    /**
     * 当前应当被采集的实例。
     *
     * <p>实现<b>不能</b>把「实例已死」表达成「这次返回空列表」—— 调用方需要区分
     * 「服务下线了」和「发现源自己挂了」，这两种情况的处置动作完全不同。
     * 发现源自身故障一律抛异常，由调用方记为失败并保留上一轮的 target 集合。
     */
    List<ScrapeTarget> discover();
}