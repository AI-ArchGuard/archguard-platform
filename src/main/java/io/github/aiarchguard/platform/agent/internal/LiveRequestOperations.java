package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentRequestView;
import java.util.UUID;

/** Internal creation foundation only; no HTTP, dispatcher, credential consumption or network binding. */
public interface LiveRequestOperations {
    AgentRequestView create(UUID project, UUID batch, UUID template, String idempotencyKey,
        String origin, boolean secure);
}
