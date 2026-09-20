package io.github.aiarchguard.platform.scanjob.internal;

import io.github.aiarchguard.platform.scanjob.ScanJobOutcome;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import java.time.Instant;
import java.util.UUID;

public record ScanJobRecord(UUID id, UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
                            String idempotencyKey, String requestSha256, ScanJobStatus status,
                            ScanJobOutcome outcome, UUID createdBy, Instant createdAt, Instant startedAt,
                            Instant completedAt, Instant leaseUntil, int attempt, UUID attemptToken,
                            long version, String failureCode, String failureMessage, byte[] report,
                            String reportSha256, boolean reportPartial, String scannerVersion,
                            String schemaVersion) {
    public ScanJobRecord {
        report = report == null ? null : report.clone();
    }
    @Override public byte[] report() { return report == null ? null : report.clone(); }
}
