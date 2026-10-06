package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.PersonalEnablementInput;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AgentEnablementStore {
    Settings settings(UUID projectId);
    Settings lockSettings(UUID projectId);
    void update(UUID projectId, boolean enabled, UUID enablementId);
    void insert(Approval approval);
    Optional<Approval> find(UUID projectId, UUID id);
    boolean revoke(UUID id, UUID actor, Instant at);
    boolean hasHistory(UUID projectId);
    record Settings(boolean enabled, long revision, UUID enablementId) { }
    record Approval(UUID id, UUID projectId, UUID deploymentId, UUID approvedBy, Instant approvedAt,
            PersonalEnablementInput acknowledgement, Instant revokedAt) { }
}
