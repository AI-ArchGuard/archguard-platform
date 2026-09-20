package io.github.aiarchguard.platform.finding;

import java.util.List;
import java.util.UUID;

public interface FindingOperations {
    List<FindingView> list(UUID projectId, UUID jobId, int page, int size);
    EvidenceView evidence(UUID projectId, UUID jobId, UUID evidenceId);
    FindingView disposition(UUID projectId, UUID jobId, UUID findingId, FindingDisposition disposition,
                            String reason, long expectedVersion);
    List<DispositionView> dispositionHistory(UUID projectId, UUID jobId, UUID findingId);
}
