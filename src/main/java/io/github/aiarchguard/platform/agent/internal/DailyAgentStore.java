package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 日常授权与账本存储边界；事务编排负责设置、授权用量、共享日金额及请求的固定锁序。 */
public interface DailyAgentStore {
    /** 只有支持精确关联 CAS 的适配器能领取日常请求。 */
    default boolean claim(RequestLink exactBinding, UUID requester, Instant at) {
        throw new io.github.aiarchguard.platform.agent.AgentUnavailableException();
    }
    /** 账本能力由适配器明确提供；旧装饰器没有实现时继续失败关闭。 */
    default LiveMonetaryBudgetStore money() { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default Optional<Attempt> attempt(UUID project, UUID request) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default void lockRequest(UUID project, UUID request) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default void insertAttempt(Attempt attempt, Instant at) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default void reserveUsage(UUID project, UUID grant, UUID request, long reserved) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default void finish(Attempt attempt, io.github.aiarchguard.platform.agent.LiveTokenUsage usage, Long actual, Instant at) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    /** 仅新插入停止记录时返回 true；冲突时由调用方核对原请求。 */
    default boolean insertStop(UUID project, UUID grant, UUID request, String code, Instant at) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    default void releaseActive(UUID project, UUID grant, UUID request) { throw new io.github.aiarchguard.platform.agent.AgentUnavailableException(); }
    record Attempt(UUID requestId, UUID projectId, UUID grantId, UUID attemptId, UUID deploymentId,
            UUID credentialVersion, long settingsRevision, String runtimeBindingDigest,
            java.time.LocalDate day, String priceVersion, long reserved, String state, Long actual) {}
    /** 未声明日常能力的适配器失败关闭；真实 JDBC 实现使用 V18 精确关联。 */
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
