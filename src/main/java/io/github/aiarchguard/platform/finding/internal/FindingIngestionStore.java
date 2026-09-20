package io.github.aiarchguard.platform.finding.internal;

import java.util.List;
import java.util.UUID;

public interface FindingIngestionStore {
    void store(UUID jobId, List<NormalizedEvidence> evidences, List<NormalizedFinding> findings);
}
