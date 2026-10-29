package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 对外返回请求状态、不可变版本绑定、已校验结果和用量。候选输入及模型原文不在此视图中，结果不能改写扫描事实或门禁。 */
public record AgentRequestView(UUID id, UUID projectId, UUID requesterId, String traceId, String purpose,
        String state, VersionBindings bindings, AgentResult result, AgentFailure failure, AgentUsage usage,
        Instant createdAt, Instant updatedAt) {
    /** 冻结产生请求时的事实、Prompt、模型、Schema、价格及摘要，便于重放比对与事后追溯。 */
    public record VersionBindings(UUID scanJobId, String reportSha256, UUID prHeadRevisionId,
            List<UUID> findingIds, List<DocumentVersionBinding> documentVersions, String promptVersion,
            String modelProfileVersion, String modelProtocolVersion, String modelId,
            String outputSchemaVersion, String priceCatalogVersion, String inputDigest) {}
    public record DocumentVersionBinding(UUID documentVersionId, String contentSha256) {}
    public record AgentResult(String conclusion, List<String> ruleBasis, List<String> claims,
            List<Suggestion> suggestions, List<VerifiedCitation> citations, String evidenceCoverage) {}
    public record Suggestion(String text, String kind, boolean requiresHumanReview) {}
    /** 服务端根据获授权候选重建的引用定位；模型返回的任意内部 ID 不能直接成为该对象。 */
    public record VerifiedCitation(String citationId, String source, String label, UUID projectId,
            UUID scanJobId, String reportSha256, UUID evidenceId, UUID documentVersionId,
            String contentSha256, Integer fragmentIndex, String fragmentSha256) {}
    public record AgentFailure(String code, String message) {}
    /** 估计与实际费用分别保留；actualCostMicrousd 为空表示费用未知，不能当作零费用。 */
    public record AgentUsage(int inputTokens, int outputTokens, long latencyMs, Long providerLatencyMs,
            long estimatedCostMicrousd, Long actualCostMicrousd, String providerResponseId,
            String actualModelId) {}
}
