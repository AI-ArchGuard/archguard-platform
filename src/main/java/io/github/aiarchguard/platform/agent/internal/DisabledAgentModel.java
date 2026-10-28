package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentModelPort;
import org.springframework.stereotype.Component;

/** 原模型端口的默认关闭实现；available 恒为 false，误调用也显式失败，不包含网络能力。 */
@Component
final class DisabledAgentModel implements AgentModelPort {
    @Override public boolean available() { return false; }
    @Override public ModelResponse explain(ModelInput input) {
        throw new IllegalStateException("No approved model adapter is configured");
    }
}
