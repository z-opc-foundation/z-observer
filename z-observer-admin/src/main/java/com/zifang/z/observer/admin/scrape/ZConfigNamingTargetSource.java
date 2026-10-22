package com.zifang.z.observer.admin.scrape;

import com.zifang.z.config.client.naming.ZNamingService;
import com.zifang.z.config.common.model.ZNamingInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 z-config naming 拿 target 列表（D1：不另造服务发现）。
 *
 * <p>指标端点挂在实例自己的 HTTP 端口上（默认 9100），所以 ip:port 直接来自注册项。
 * <b>不筛 healthy</b>：unhealthy 的实例恰恰是最该去采的 —— 它还占着端口，
 * 真去拉一次就能区分「进程死了」和「进程活着但不健康」，两者不该混成一条告警。
 *
 * <p>naming 本身不可用时<b>抛异常</b>而不是返回空列表，理由见 {@link TargetRegistry}：
 * 把发现源故障当成「全部实例消失」会一次性造出全集群的 absent 告警。
 */
public final class ZConfigNamingTargetSource implements TargetSource {

    private final ZNamingService naming;
    private final List<String> serviceNames;
    private final int metricsPort;
    private final String path;

    public ZConfigNamingTargetSource(ZNamingService naming, List<String> serviceNames,
                                     int metricsPort) {
        this(naming, serviceNames, metricsPort, ScrapeTarget.DEFAULT_METRICS_PATH);
    }

    public ZConfigNamingTargetSource(ZNamingService naming, List<String> serviceNames,
                                     int metricsPort, String path) {
        if (naming == null) {
            throw new IllegalArgumentException("naming 客户端必填");
        }
        if (serviceNames == null || serviceNames.isEmpty()) {
            throw new IllegalArgumentException("至少要订阅一个服务名");
        }
        this.naming = naming;
        this.serviceNames = new ArrayList<String>(serviceNames);
        this.metricsPort = metricsPort;
        this.path = path == null || path.isEmpty()
                ? ScrapeTarget.DEFAULT_METRICS_PATH : path;
    }

    @Override
    public String name() {
        return "z-config-naming";
    }

    @Override
    public List<ScrapeTarget> discover() {
        List<ScrapeTarget> out = new ArrayList<ScrapeTarget>();
        for (String service : serviceNames) {
            List<ZNamingInstance> instances = naming.getAllInstances(service);
            if (instances == null) {
                continue;
            }
            for (ZNamingInstance inst : instances) {
                if (inst.getIp() == null || inst.getPort() == null) {
                    continue;
                }
                String id = targetId(service, inst);
                out.add(new ScrapeTarget(id, "http://" + inst.getIp()
                        + ":" + inst.getPort() + path));
            }
        }
        return out;
    }

    /**
     * target id 形如 {@code z-gw@10.0.0.7:9100}。
     *
     * <p>必须带实例地址：同一个服务起三个 pod 是三个 target，
     * 只用服务名做 id 会让三份采集结果互相覆盖，
     * 而且挂掉一个时另外两个还「在」，absent 永远不会触发。
     */
    static String targetId(String service, ZNamingInstance inst) {
        return service + "@" + inst.getIp() + ":" + inst.getPort();
    }

    public List<String> serviceNames() {
        return new ArrayList<String>(serviceNames);
    }
}