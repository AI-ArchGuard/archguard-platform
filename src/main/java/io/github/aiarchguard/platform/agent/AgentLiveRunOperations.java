package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** Explicit personal live entry; never a replacement for the existing synthetic request API. */
public interface AgentLiveRunOperations {
    void authorize(UUID project, String origin, boolean secure);
    AgentRequestView run(UUID project, LiveAgentRunInput input, String idempotencyKey, String origin, boolean secure);
}
