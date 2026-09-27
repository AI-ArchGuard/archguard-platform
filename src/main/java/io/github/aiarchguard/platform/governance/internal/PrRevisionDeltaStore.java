package io.github.aiarchguard.platform.governance.internal;

import java.util.Optional;
import java.util.UUID;

public interface PrRevisionDeltaStore {
    record Candidate(UUID gateId, UUID comparisonId, String fingerprintVersion) { }
    Optional<Candidate> candidate(UUID projectId, UUID repositoryId, String externalId,
                                  String headSha, String targetBranch, UUID ruleSetVersionId);
}
