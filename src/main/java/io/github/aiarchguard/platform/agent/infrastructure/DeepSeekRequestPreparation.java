package io.github.aiarchguard.platform.agent.infrastructure;

import io.github.aiarchguard.platform.agent.AgentModelPort.ModelInput;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;

/** RED pure preparation seam; deliberately unimplemented and never a Spring bean. */
public final class DeepSeekRequestPreparation {
    public byte[] prepare(ModelInput input) { throw new AgentUnavailableException(); }
}
