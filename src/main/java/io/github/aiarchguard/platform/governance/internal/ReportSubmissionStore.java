package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.ReportSubmissionView;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReportSubmissionStore {
    record Stored(ReportSubmissionView view, String idempotencyKey, UUID scanJobId,
                  String scannerVersion, String schemaVersion) { }
    Optional<Stored> byKey(UUID projectId, String key);
    Optional<Stored> byDigest(UUID projectId, String digest);
    Optional<Stored> find(UUID projectId, UUID repositoryId, UUID id);
    Optional<UUID> latestCompletedGateForPullRequest(UUID projectId, UUID repositoryId,
                                                     String externalId, String headSha);
    boolean insert(ReportSubmissionView value, String key, UUID scanJobId,
                   String scannerVersion, String schemaVersion, UUID actorId);
    boolean complete(UUID projectId, UUID repositoryId, UUID id, UUID gateId, Instant at);
}
