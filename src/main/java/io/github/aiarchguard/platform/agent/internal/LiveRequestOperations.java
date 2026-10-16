package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentRequestView;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Internal creation foundation only; no HTTP, dispatcher, credential consumption or network binding. */
public interface LiveRequestOperations {
    AgentRequestView create(UUID project, UUID batch, UUID template, String idempotencyKey,
        String origin, boolean secure);

    /** Bounded production creation must not silently invoke a legacy unbounded implementation. */
    default AgentRequestView create(UUID project, UUID batch, UUID template, String idempotencyKey,
            String origin, boolean secure, LongSupplier remainingNanos) {
        throw new io.github.aiarchguard.platform.agent.AgentUnavailableException();
    }
}
