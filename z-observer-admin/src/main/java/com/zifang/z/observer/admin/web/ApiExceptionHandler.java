package com.zifang.z.observer.admin.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常出口。
 *
 * <p>查询面失败（{@link com.zifang.z.observer.admin.metric.PrometheusQueryException}）返 502 而不是 500：
 * 它不是收端自己的 bug，是下游 Prometheus 不可用。控制台据此显示「指标源不可达」，
 * 而不是一句无用的「服务器错误」——这两者在故障时的排查动作完全不同。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TraceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(TraceNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(com.zifang.z.observer.admin.metric.PrometheusQueryException.class)
    public ResponseEntity<Map<String, Object>> prometheusDown(
            com.zifang.z.observer.admin.metric.PrometheusQueryException e) {
        return body(HttpStatus.BAD_GATEWAY, "指标源不可达: " + e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("status", Integer.valueOf(status.value()));
        m.put("error", message);
        return ResponseEntity.status(status).body(m);
    }
}
