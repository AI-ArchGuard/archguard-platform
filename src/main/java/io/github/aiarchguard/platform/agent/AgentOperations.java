package io.github.aiarchguard.platform.agent;

import java.util.UUID;

public interface AgentOperations {
    AgentRequestView create(UUID projectId, String idempotencyKey, CreateAgentRequest request);
    AgentRequestView get(UUID projectId, UUID requestId);
}
