package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 日常授权存储边界；设置先锁，随后才锁授权用量，不提供发送或记账接口。 */
public interface DailyAgentStore {
    /** RED seam: no V18 queries or writes until the creation checkpoint is approved. */
    default Optional<RequestLink> findRequest(UUID project, UUID request) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default Optional<RequestLink> findRequestByKey(UUID project, UUID owner, String key) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default void insertRequest(RequestLink link) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default void acquireActive(UUID project, UUID grant, UUID request) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default Optional<DailyStop> findStop(UUID project, UUID grant) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    record RequestLink(UUID requestId, UUID projectId, UUID grantId, UUID riskAcknowledgementId,
            UUID credentialVersion, long settingsRevision, String runtimeBindingDigest, String payloadSha256,
            String candidatesSha256, Instant createdAt) {}
    Settings settings(UUID project);
    Settings lockSettings(UUID project);
    void updateSettings(UUID project, boolean enabled, UUID grant);
    Optional<Risk> findRisk(UUID project, UUID id);
    Optional<Risk> findRiskByKey(UUID project, UUID owner, String key);
    boolean insertRisk(Risk risk);
    boolean revokeRisk(UUID risk, UUID actor, Instant at);
    Optional<Grant> findGrant(UUID project, UUID id);
    Optional<Grant> findGrantByKey(UUID project, UUID owner, String key);
    boolean insertGrant(Grant grant);
    boolean revokeGrant(UUID grant, UUID actor, Instant at);
    Usage lockUsage(UUID project, UUID grant);
    void lockRiskUsage(UUID project, UUID risk);
    void initializeUsage(UUID project, UUID grant);
    record Settings(boolean enabled, long revision, UUID grantId) {}
    record Revocation(UUID actor, Instant at) {}
    /** 完整绑定与原确认分开保存；动作时间不能成为来源核对事实。 */
    record Risk(UUID id, UUID projectId, UUID deploymentId, UUID ownerId, String key, String inputDigest,
            String runtimeBindingDigest, UUID credentialVersion, String dataPolicyVersion,
            Instant acknowledgedAt, Instant expiresAt, DailyBindingSnapshot binding, DailyRiskInput input,
            Revocation revocation) {}
    record Grant(UUID id, UUID projectId, UUID deploymentId, UUID ownerId, UUID riskAcknowledgementId,
            String key, String inputDigest, String runtimeBindingDigest, UUID credentialVersion,
            List<DailyPurpose> allowedPurposes, int maxAttempts, long maxCostMicrousd, Instant approvedAt,
            Instant expiresAt, Revocation revocation, Usage usage) {
        public Grant { allowedPurposes = List.copyOf(allowedPurposes); }
    }
    record Usage(int attempts, long reservedMicrousd, long spentMicrousd, UUID activeRequestId) {}
}
