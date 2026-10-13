package com.zifang.z.observer.admin.scrape;

/** 一个被采集的 target。 */
public final class ScrapeTarget {

    private final String id;
    private final String url;

    public ScrapeTarget(String id, String url) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("target id 必填");
        }
        if (url == null || !url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("采集地址必须是 http/https: " + url);
        }
        this.id = id;
        this.url = url;
    }

    public String id() {
        return id;
    }

    public String url() {
        return url;
    }

    /** 这个 target 该带哪些标签（app/instance 等），也决定告警里怎么称呼它。 */
    public String instanceLabel() {
        return id;
    }

    @Override
    public String toString() {
        return id + " -> " + url;
    }
}
