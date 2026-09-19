package io.github.aiarchguard.platform.finding.internal;

import io.github.aiarchguard.platform.finding.DispositionView;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingDisposition;
import io.github.aiarchguard.platform.finding.FindingView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FindingStore {
    List<FindingView> list(UUID projectId, UUID jobId, int page, int size);
    Optional<FindingView> find(UUID projectId, UUID jobId, UUID findingId);
    Optional<EvidenceView> findEvidence(UUID projectId, UUID jobId, UUID evidenceId);
    boolean updateDisposition(UUID findingId, FindingDisposition previous, FindingDisposition next,
                              long expectedVersion, String reason, UUID actorId, Instant now, UUID historyId);
    List<DispositionView> history(UUID findingId);
}
