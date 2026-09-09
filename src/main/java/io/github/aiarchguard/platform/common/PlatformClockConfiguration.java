package io.github.aiarchguard.platform.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PlatformClockConfiguration {
    @Bean
    Clock platformClock() {
        return Clock.systemUTC();
    }
}
