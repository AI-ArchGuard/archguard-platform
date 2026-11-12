package io.github.aiarchguard.platform.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.internal.LiveAdapterBundle;
import io.github.aiarchguard.platform.agentcredential.CredentialUseSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 明确由共享执行器关闭并等待适配器终止，容器不再次推断 close。 */
@Configuration(proxyBeanMethods = false)
class DeepSeekAdapterConfiguration {
    @Bean(destroyMethod = "")
    LiveAdapterBundle deepSeekProductionAdapters(ObjectMapper mapper, CredentialUseSource source) {
        return new DeepSeekProductionAdapters(mapper, source);
    }
}
