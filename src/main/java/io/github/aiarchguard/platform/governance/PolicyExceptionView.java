package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

public record PolicyExceptionView(UUID id, UUID projectId, UUID repositoryId, String targetBranch,
                                  UUID ruleSetVersionId, PolicyExceptionScopeType scopeType, String scopeValue,
                                  String reason, Instant effectiveAt, Instant expiresAt, UUID createdBy,
                                  Instant createdAt, UUID revokedBy, Instant revokedAt, String revocationReason,
                                  UUID currentVersionId, int version, PolicyExceptionStatus status) { }
