package io.github.aiarchguard.platform.scanjob.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScanJobStore {
    void insert(ScanJobRecord job);
    Optional<ScanJobRecord> findByIdempotency(UUID projectId, String key);
    Optional<ScanJobRecord> find(UUID projectId, UUID jobId);
    Optional<ScanJobRecord> findById(UUID jobId);
    List<ScanJobRecord> list(UUID projectId, int page, int size);
    Optional<ScanJobRecord> cancel(UUID projectId, UUID jobId, Instant now);
    Optional<ScanJobRecord> claim(Instant now, Instant leaseUntil, UUID attemptToken, int maxAttempts);
    boolean heartbeat(UUID jobId, UUID attemptToken, Instant leaseUntil);
    boolean complete(UUID jobId, UUID attemptToken, int exitCode, byte[] report, String sha256,
                     boolean partial, String scannerVersion, String schemaVersion, Instant now);
    boolean fail(UUID jobId, UUID attemptToken, String code, String message, byte[] partialReport,
                 String sha256, Instant now);
    boolean existsForProject(UUID projectId);
}
