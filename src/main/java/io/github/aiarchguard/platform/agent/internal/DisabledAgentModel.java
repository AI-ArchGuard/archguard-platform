package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentModelPort;
import org.springframework.stereotype.Component;

@Component
final class DisabledAgentModel implements AgentModelPort {
    @Override public boolean available() { return false; }
    @Override public ModelResponse explain(ModelInput input) {
        throw new IllegalStateException("No approved model adapter is configured");
    }
}
