package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.LiveBatchView;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface LiveAccountingStore {
    record Batch(LiveBatchView view, UUID deploymentId, UUID inventoryId, LiveManifest manifest) { }
    record Usage(long reserved, long spent, int attempts) { }
    record Attempt(UUID requestId, UUID projectId, UUID batchId, UUID templateId, UUID attemptId,
            UUID deploymentId, LocalDate day, String priceVersion, long reservation, String outcome, Long actual) { }
    void insert(Batch batch);
    Optional<Batch> batch(UUID project, UUID batch);
    Usage lockBatch(UUID batch);
    boolean revoke(UUID batch, UUID actor, Instant at);
    Usage lockBudget(String scope, UUID scopeId, LocalDate day);
    void reserveBudget(String scope, UUID scopeId, LocalDate day, long reservation);
    void reserveBatch(UUID batch, long reservation);
    Optional<Attempt> attempt(UUID project, UUID request);
    void insertAttempt(Attempt attempt, UUID enablement, UUID credential, long revision, Instant now);
    void finish(Attempt attempt, LiveTokenUsage usage, Long cost, Instant now);
}
