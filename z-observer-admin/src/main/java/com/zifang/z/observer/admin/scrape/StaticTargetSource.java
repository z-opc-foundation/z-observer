package com.zifang.z.observer.admin.scrape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 静态 target 列表。z-config 还没接上、或者本地只想盯两三个服务时用。
 *
 * <p>保留它不是「另造服务发现」——发现源可换是 D1 的约束，但 M1 起步阶段 z-config
 * 未必先就绪，配置里手写几个 target 就能立刻跑通采集与 absent 闭环。
 */
public final class StaticTargetSource implements TargetSource {

    private final List<ScrapeTarget> targets;

    public StaticTargetSource(List<ScrapeTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("静态 target 不能为空");
        }
        this.targets = Collections.unmodifiableList(new ArrayList<ScrapeTarget>(targets));
    }

    public static StaticTargetSource ofUrls(String... urls) {
        List<ScrapeTarget> list = new ArrayList<ScrapeTarget>();
        for (String url : urls) {
            list.add(new ScrapeTarget(url, url));
        }
        return new StaticTargetSource(list);
    }

    public static StaticTargetSource ofUrlsAndPorts(String... urlAndPorts) {
        if (urlAndPorts.length % 2 != 0) {
            throw new IllegalArgumentException("参数应为 url、port 成对给出");
        }
        List<ScrapeTarget> list = new ArrayList<ScrapeTarget>();
        for (int i = 0; i < urlAndPorts.length; i += 2) {
            String host = urlAndPorts[i];
            int port = Integer.parseInt(urlAndPorts[i + 1]);
            list.add(new ScrapeTarget(host + ":" + port, "http://" + host + ":" + port + "/metrics"));
        }
        return new StaticTargetSource(list);
    }

    @Override
    public String name() {
        return "static";
    }

    @Override
    public List<ScrapeTarget> discover() {
        return new ArrayList<ScrapeTarget>(targets);
    }

    @Override
    public String toString() {
        return "StaticTargetSource" + Arrays.toString(targets.toArray());
    }
}