package com.zifang.z.observer.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** z-observer 收端：收上报、存日志与链路、查指标、跑规则、发告警。 */
@SpringBootApplication
@EnableConfigurationProperties(ObserverAdminProperties.class)
@EnableScheduling
public class ObserverAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(ObserverAdminApplication.class, args);
    }
}
