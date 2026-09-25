package io.github.aiarchguard.platform.governance;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ComparisonView(UUID id, UUID baselineVersionId, UUID candidateJobId, String candidateReportSha256,
                             String fingerprintVersion, Instant createdAt, List<ClassifiedFinding> findings) {
    public ComparisonView { findings = List.copyOf(findings); }
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
