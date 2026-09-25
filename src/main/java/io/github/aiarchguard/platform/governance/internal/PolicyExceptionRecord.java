package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.ClassifiedFinding;
import io.github.aiarchguard.platform.governance.PolicyExceptionScopeType;
import io.github.aiarchguard.platform.governance.PolicyExceptionStatus;
import io.github.aiarchguard.platform.governance.PolicyExceptionView;
import java.time.Instant;
import java.util.UUID;

public record PolicyExceptionRecord(UUID id, UUID projectId, UUID repositoryId, String targetBranch,
                                    UUID ruleSetVersionId, PolicyExceptionScopeType scopeType, String scopeValue,
                                    String reason, Instant effectiveAt, Instant expiresAt, UUID createdBy,
                                    Instant createdAt, UUID revokedBy, Instant revokedAt,
                                    String revocationReason, UUID revocationVersionId) {
    public boolean validAt(Instant at) {
        return !at.isBefore(createdAt) && !at.isBefore(effectiveAt) && at.isBefore(expiresAt)
            && (revokedAt == null || at.isBefore(revokedAt));
    }
    public boolean matches(ClassifiedFinding finding) {
        return scopeType == PolicyExceptionScopeType.FINGERPRINT
            ? scopeValue.equals(finding.fingerprint()) : scopeValue.equals(finding.ruleId());
    }
    public PolicyExceptionView viewAt(Instant at) {
        PolicyExceptionStatus status = revokedAt != null && !at.isBefore(revokedAt)
            ? PolicyExceptionStatus.REVOKED
            : at.isBefore(effectiveAt) ? PolicyExceptionStatus.PENDING
            : at.isBefore(expiresAt) ? PolicyExceptionStatus.ACTIVE : PolicyExceptionStatus.EXPIRED;
        return new PolicyExceptionView(id, projectId, repositoryId, targetBranch, ruleSetVersionId,
            scopeType, scopeValue, reason, effectiveAt, expiresAt, createdBy, createdAt,
            revokedBy, revokedAt, revocationReason, revocationVersionId == null ? id : revocationVersionId,
            revocationVersionId == null ? 1 : 2, status);
    }
}
