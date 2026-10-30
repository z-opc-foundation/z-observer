package com.zifang.z.observer.starter.metrics;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** 暴露端点的 servlet 外壳，只为把 ServletRegistrationBean 接上 Handler。 */
public class PrometheusServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final PrometheusHandler handler;

    public PrometheusServlet(PrometheusHandler handler) {
        this.handler = handler;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handler.handle(request, response);
    }
}