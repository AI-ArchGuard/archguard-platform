package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PolicyExceptionOperations {
    PolicyExceptionView create(UUID projectId, UUID repositoryId, String targetBranch,
                               UUID ruleSetVersionId, PolicyExceptionScopeType scopeType, String scopeValue,
                               String reason, Instant effectiveAt, Instant expiresAt);
    PolicyExceptionView revoke(UUID projectId, UUID repositoryId, UUID exceptionId, String reason);
    List<PolicyExceptionView> list(UUID projectId, UUID repositoryId, String targetBranch, UUID ruleSetVersionId);
    PolicyExceptionView get(UUID projectId, UUID repositoryId, UUID exceptionId);
}
