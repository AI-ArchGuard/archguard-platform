package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.ClassifiedFinding;
import io.github.aiarchguard.platform.governance.PolicyExceptionScopeType;
import io.github.aiarchguard.platform.governance.PolicyExceptionStatus;
import io.github.aiarchguard.platform.governance.PolicyExceptionView;
import java.time.Instant;
import java.util.UUID;

/**
 * 内部例外事实，将不可变创建记录与可选撤销版本组合，供策略判断和对外状态投影。
 * 项目/仓库/分支/规则版本的范围由上层查询限定，单项匹配只检查规则或逻辑指纹。
 */
public record PolicyExceptionRecord(UUID id, UUID projectId, UUID repositoryId, String targetBranch,
                                    UUID ruleSetVersionId, PolicyExceptionScopeType scopeType, String scopeValue,
                                    String reason, Instant effectiveAt, Instant expiresAt, UUID createdBy,
                                    Instant createdAt, UUID revokedBy, Instant revokedAt,
                                    String revocationReason, UUID revocationVersionId) {
    /** 生效区间为 [effectiveAt, expiresAt)，还必须已创建且在撤销时刻之前。 */
    public boolean validAt(Instant at) {
        return !at.isBefore(createdAt) && !at.isBefore(effectiveAt) && at.isBefore(expiresAt)
            && (revokedAt == null || at.isBefore(revokedAt));
    }
    /** 只匹配稳定指纹或规则 ID；调用方仍须结合 validAt，不能仅凭命中就豁免。 */
    public boolean matches(ClassifiedFinding finding) {
        return scopeType == PolicyExceptionScopeType.FINGERPRINT
            ? scopeValue.equals(finding.fingerprint()) : scopeValue.equals(finding.ruleId());
    }
    /** 按给定时间计算展示状态；撤销后公开版本号为 2，原创建记录与历史求值不被修改。 */
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
