package io.github.aiarchguard.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ArchGuardPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchGuardPlatformApplication.class, args);
    }
}
