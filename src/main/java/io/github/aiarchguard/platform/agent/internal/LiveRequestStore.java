package io.github.aiarchguard.platform.agent.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Immutable request-to-approval association, separate from model attempts and public request DTOs. */
public interface LiveRequestStore {
    record Link(UUID requestId, UUID projectId, UUID batchId, UUID templateId, UUID enablementId,
            UUID credentialVersion, long settingsRevision, Instant createdAt) { }
    void insert(Link link);
    Optional<Link> find(UUID projectId, UUID requestId);
}
