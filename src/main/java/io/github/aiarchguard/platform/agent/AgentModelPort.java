package io.github.aiarchguard.platform.agent;

import java.util.List;

/** Untrusted model boundary. Stage 4D/4E accept only synthetic adapters until real egress is approved. */
public interface AgentModelPort {
    default boolean syntheticOnly() { return false; }
    boolean available();
    ModelResponse explain(ModelInput input) throws Exception;

    record ModelInput(String purpose, String schemaVersion, String promptVersion, String findingRef,
            String ruleId, String ruleVersion, String severity, String message, String subject,
            List<EvidenceInput> evidence, List<DocumentInput> documents, List<FindingInput> findings) {}
    record FindingInput(String findingRef, String ruleId, String ruleVersion, String severity,
            String message, String subject, List<EvidenceInput> evidence) {}
    record EvidenceInput(String citationId, String kind, String summary, Integer startLine, Integer endLine) {}
    record DocumentInput(String citationId, String contentSha256, String excerpt) {}
    record ModelResponse(String rawJson, int inputTokens, int outputTokens, long latencyMs,
            String providerResponseId, String actualModelId) {}
}
