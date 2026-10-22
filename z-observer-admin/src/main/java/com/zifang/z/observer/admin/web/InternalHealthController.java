package com.zifang.z.observer.admin.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部健康端点（<b>免鉴权</b>）。
 *
 * <p><b>存在的理由</b>：k8s 的 readiness/liveness 探针不会带 Authorization 头
 * ⇒ 鉴权一旦加上，探针打过来是 401 ⇒ Pod 永远不就绪。
 * 于是必须留一条**只答活着不活着**的路。
 *
 * <p><b>为什么不用 {@code /api/logs/status}</b>：那条是业务查询面（返回日志条数），
 * 把它留成免鉴权就等于在公开面上留了一个业务读端点 ——
 * 而本卡要收的正是「读端点也匿名可读」这个洞。
 * ⇒ 单独开一个<b>不含任何业务数据</b>的健康端点，把业务面整条挡上。
 *
 * <p>探针口径见 {@code deploy/k8s/02-deployment.yaml}。
 */
@RestController
@RequestMapping("/api/internal")
public class InternalHealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("status", "UP");
        out.put("service", "z-observer-admin");
        return out;
    }
}
