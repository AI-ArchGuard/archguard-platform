package io.github.aiarchguard.platform.governance.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyExceptionStore {
    void insert(PolicyExceptionRecord value);
    Optional<PolicyExceptionRecord> find(UUID projectId, UUID repositoryId, UUID exceptionId);
    List<PolicyExceptionRecord> list(UUID projectId, UUID repositoryId, String branch, UUID ruleSetVersionId);
    boolean revoke(UUID exceptionId, UUID versionId, String reason, UUID actorId, Instant at);
}
