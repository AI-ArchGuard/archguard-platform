package io.github.aiarchguard.platform.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 集中提供 UTC Clock，让领域时间判断可由测试时钟替换。 */
@Configuration(proxyBeanMethods = false)
class PlatformClockConfiguration {
    @Bean
    Clock platformClock() {
        return Clock.systemUTC();
    }
}
