package io.github.aiarchguard.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 模块化单体启动入口；调度器驱动扫描与 Agent 的后台协调，具体能力仍受各模块开关约束。 */
@SpringBootApplication
@EnableScheduling
public class ArchGuardPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchGuardPlatformApplication.class, args);
    }
}
