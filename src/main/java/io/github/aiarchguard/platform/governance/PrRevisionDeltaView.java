package io.github.aiarchguard.platform.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/** Informational PR-to-PR difference; never used to evaluate the baseline gate. */
public record PrRevisionDeltaView(String reference, String availability, String currentHeadSha,
                                  String previousHeadSha, UUID currentGateEvaluationId,
                                  UUID previousGateEvaluationId, UUID ruleSetVersionId,
                                  String fingerprintVersion, List<ClassifiedFinding> findings) {
    public PrRevisionDeltaView { findings = List.copyOf(findings); }
    @JsonProperty("newCount") public long newCount() {
        return findings.stream().filter(f -> f.classification() == Classification.NEW).count();
    }
    @JsonProperty("existingCount") public long existingCount() {
        return findings.stream().filter(f -> f.classification() == Classification.EXISTING).count();
    }
    @JsonProperty("resolvedCount") public long resolvedCount() {
        return findings.stream().filter(f -> f.classification() == Classification.RESOLVED).count();
    }
}
