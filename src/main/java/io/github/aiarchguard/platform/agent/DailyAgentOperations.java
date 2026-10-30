package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 个人 Project 日常 Agent 的独立应用边界。仅声明契约，不注册运行时或授予费用；
 * 服务仍须验证当前 owner、Project 角色、精确绑定、期限、额度与资源归属。
 */
public interface DailyAgentOperations {
    /** 在读取选材正文之前校验 owner、Viewer、Origin 和传输条件；摘要还须 Maintainer。 */
    void authorizeSelectionWrite(UUID project, String origin, boolean secure);
    /** 在读取控制正文之前校验 owner、Maintainer、Origin 和传输条件。 */
    void authorizeControlWrite(UUID project, String origin, boolean secure);
    DailyContext context(UUID project);
    DailySettings settings(UUID project);
    DailySettings updateSettings(UUID project, long revision, DailySettingsInput input);
    Creation<DailyRiskView> acknowledge(UUID project, String key, DailyRiskInput input);
    DailyRiskView getRisk(UUID project, UUID risk);
    DailyRiskView revokeRisk(UUID project, UUID risk);
    Creation<DailyGrantView> approveGrant(UUID project, String key, DailyGrantInput input);
    DailyGrantView getGrant(UUID project, UUID grant);
    DailyGrantView revokeGrant(UUID project, UUID grant);
    Creation<DailyRequestView> create(UUID project, String key, DailyRequestInput input);
    DailyRequestView get(UUID project, UUID request);
    DailyRequestView lookup(UUID project, String key);
    DailyRequestPage list(UUID project, int limit, String cursor);

    /** 只供 HTTP 适配器选择首次 201/重放 200；不作为 wire wrapper。 */
    record Creation<T>(T value, boolean created) {}

    enum DailyPurpose { FINDING_EXPLANATION, PR_SUMMARY }
    enum DailyUnavailableReason {
        PROJECT_DISABLED, DEPLOYMENT_DISABLED, RUNTIME_NOT_READY, RISK_ACK_REQUIRED,
        RISK_ACK_REVOKED, RISK_ACK_EXPIRED, GRANT_REQUIRED, GRANT_REVOKED, GRANT_STOPPED,
        GRANT_EXPIRED, CREDENTIAL_CHANGED, BINDING_CHANGED, EVIDENCE_EXPIRED, GRANT_EXHAUSTED
    }
    enum DailyGrantState { ACTIVE, REVOKED, STOPPED, EXPIRED, EXHAUSTED }
    enum DailyCostState { NOT_ATTEMPTED, PENDING, SETTLED, UNKNOWN }

    /** 本地必要条件及技术上限不等于 Key 有效、供应商可用或所有者花费许可。 */
    record DailyContext(String schemaVersion, DailyBindingSnapshot runtimeBinding, String runtimeBindingDigest,
            List<String> unknowns, DailyLimits limits, DailySettings settings, DailyGrantView currentGrant,
            boolean runtimeReady, DailyUnavailableReason unavailableReason) {
        public DailyContext { unknowns = unknowns == null ? null : List.copyOf(unknowns); }
    }
    /** 完整的非敏感固定绑定；摘要按固定字段顺序和来源 kind 排序计算，不能包含当前读取时间。 */
    record DailyBindingSnapshot(UUID deploymentId, UUID ownerId, String runtimeRevision, UUID credentialVersion,
            String modelAlias, String mappingSnapshot, List<String> allowedResponseModels, String modelProtocolVersion,
            String explanationPromptVersion, String summaryPromptVersion, String outputSchemaVersion,
            String priceCatalogVersion, String dataPolicyVersion, String secretCheckRef, String networkCheckRef,
            DailyEvidence evidence) {
        public DailyBindingSnapshot { allowedResponseModels = allowedResponseModels == null ? null : List.copyOf(allowedResponseModels); }
    }
    record DailyEvidence(List<DailySource> sources, Instant priceCatalogExpiresAt) {
        public DailyEvidence { sources = sources == null ? null : List.copyOf(sources); }
    }
    /** 来源核对时间是操作员事实，不能由页面读取时间或旧确认替代。 */
    record DailySource(String kind, String url, String version, Instant checkedAt) {}
    record DailyLimits(long reservationMicrousd, long perRequestMicrousd, long projectDayMicrousd,
            long deploymentDayMicrousd, long maxEvidenceAgeSeconds, long maxRiskAgeSeconds) {}
    record DailySettings(boolean enabled, long revision, UUID grantId, boolean modelCallsAvailable,
            DailyUnavailableReason unavailableReason) {}
    record DailySettingsInput(String schemaVersion, Boolean enabled, UUID grantId) {}
    record DailyRiskInput(String schemaVersion, String runtimeBindingDigest, Instant expiresAt,
            Boolean sourcesReviewed, Boolean riskAccepted, Boolean materialRightsAccepted) {}
    record DailyRiskView(UUID id, UUID projectId, UUID ownerId, Instant acknowledgedAt, Instant expiresAt,
            String runtimeBindingDigest, UUID credentialVersion, String dataPolicyVersion, Instant revokedAt) {}
    /** 不设默认次数、金额或有效期；依赖次数的金额上限须由服务与数据库共同验证。 */
    record DailyGrantInput(String schemaVersion, UUID riskAcknowledgementId, String runtimeBindingDigest,
            List<DailyPurpose> allowedPurposes, int maxAttempts, long maxCostMicrousd, Instant expiresAt,
            Boolean callsApproved) {
        public DailyGrantInput { allowedPurposes = allowedPurposes == null ? null : List.copyOf(allowedPurposes); }
    }
    record DailyGrantView(UUID id, UUID projectId, UUID ownerId, UUID riskAcknowledgementId,
            String runtimeBindingDigest, UUID credentialVersion, List<DailyPurpose> allowedPurposes,
            int maxAttempts, long maxCostMicrousd, Instant approvedAt, Instant expiresAt,
            DailyGrantState state, Instant revokedAt, DailyStop stop, DailyUsage usage) {
        public DailyGrantView { allowedPurposes = allowedPurposes == null ? null : List.copyOf(allowedPurposes); }
    }
    record DailyStop(UUID requestId, String code, Instant createdAt) {}
    record DailyUsage(int attempts, long reservedMicrousd, long spentMicrousd, UUID activeRequestId) {}
    record DailyRequestInput(String schemaVersion, UUID grantId, Boolean selectedMaterialConfirmed, DailySelection selection) {}
    /** 只选择现有事实版本；不接收源码、自由 Prompt、URL、凭据或模型策略。 */
    record DailySelection(DailyPurpose purpose, UUID scanJobId, String reportSha256, UUID prHeadRevisionId,
            List<UUID> findingIds, List<UUID> documentVersionIds) {
        public DailySelection {
            findingIds = findingIds == null ? null : List.copyOf(findingIds);
            documentVersionIds = documentVersionIds == null ? null : List.copyOf(documentVersionIds);
        }
    }
    record DailyRequestView(String schemaVersion, AgentRequestView request, DailyAuthorization authorization,
            DailyExecution execution) {}
    record DailyAuthorization(UUID grantId, UUID riskAcknowledgementId, String runtimeBindingDigest, long settingsRevision) {}
    /** 未产生的费用保持 null；只有 SETTLED 的 actualMicrousd=0 才表示可信零费用。 */
    record DailyExecution(UUID attemptId, LocalDate accountingDay, DailyCostState costState,
            Long reservedMicrousd, Long actualMicrousd) {}
    record DailyRequestPage(List<DailyRequestView> items, String nextCursor) {
        public DailyRequestPage { items = items == null ? null : List.copyOf(items); }
    }
}
