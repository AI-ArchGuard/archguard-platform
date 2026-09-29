package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentRequestView(UUID id, UUID projectId, UUID requesterId, String traceId, String purpose,
        String state, VersionBindings bindings, AgentResult result, AgentFailure failure, AgentUsage usage,
        Instant createdAt, Instant updatedAt) {
    public record VersionBindings(UUID scanJobId, String reportSha256, UUID prHeadRevisionId,
            List<UUID> findingIds, List<DocumentVersionBinding> documentVersions, String promptVersion,
            String modelProfileVersion, String modelProtocolVersion, String modelId,
            String outputSchemaVersion, String priceCatalogVersion, String inputDigest) {}
    public record DocumentVersionBinding(UUID documentVersionId, String contentSha256) {}
    public record AgentResult(String conclusion, List<String> ruleBasis, List<String> claims,
            List<Suggestion> suggestions, List<VerifiedCitation> citations, String evidenceCoverage) {}
    public record Suggestion(String text, String kind, boolean requiresHumanReview) {}
    public record VerifiedCitation(String citationId, String source, String label, UUID projectId,
            UUID scanJobId, String reportSha256, UUID evidenceId, UUID documentVersionId,
            String contentSha256, Integer fragmentIndex, String fragmentSha256) {}
    public record AgentFailure(String code, String message) {}
    public record AgentUsage(int inputTokens, int outputTokens, long latencyMs, Long providerLatencyMs,
            long estimatedCostMicrousd, Long actualCostMicrousd, String providerResponseId,
            String actualModelId) {}
}
