package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.LiveBatchView;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 真实批次、预留、唯一尝试和不可变结算的存储端口；调用者负责固定锁序与事务，未知费用保留预留，次数不会因结算释放而恢复。
 */
public interface LiveAccountingStore {
    record Batch(LiveBatchView view, UUID deploymentId, UUID inventoryId, LiveManifest manifest,
            SyntheticBatchInventory.Proof inventoryProof, String costPolicyVersion) {
        public Batch(LiveBatchView view, UUID deploymentId, UUID inventoryId, LiveManifest manifest,
                SyntheticBatchInventory.Proof inventoryProof) {
            this(view, deploymentId, inventoryId, manifest, inventoryProof, LiveCostPolicy.VERSION);
        }
    }
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
    ReservationBinding reservationBinding(UUID project, UUID request);
    record ReservationBinding(UUID enablementId, UUID credentialVersion, long settingsRevision) { }
    void insertAttempt(Attempt attempt, UUID enablement, UUID credential, long revision, Instant now);
    void finish(Attempt attempt, LiveTokenUsage usage, Long cost, Instant now);
    /** Requires the caller's settings-to-batch transaction; never infer completion from a settled charge alone. */
    default void requireSerialProgress(Batch batch, UUID requestId) { throw new AgentUnavailableException(); }
    /** Append-only, idempotent stop; the request must belong to this exact Project and batch. */
    default void stop(UUID projectId, UUID batchId, UUID requestId, String code, Instant at) { throw new AgentUnavailableException(); }
}
