package io.github.aiarchguard.platform.scanjob;

import java.time.Instant;
import java.util.UUID;

public record ScanJobView(UUID id, UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
                          ScanJobStatus status, ScanJobOutcome outcome, UUID createdBy, Instant createdAt,
                          Instant startedAt, Instant completedAt, int attempt, long version,
                          String failureCode, String failureMessage, String reportSha256) {
}
