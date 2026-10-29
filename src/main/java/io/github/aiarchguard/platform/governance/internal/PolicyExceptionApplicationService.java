package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.GovernanceScopeInput;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.governance.PolicyExceptionNotFoundException;
import io.github.aiarchguard.platform.governance.PolicyExceptionOperations;
import io.github.aiarchguard.platform.governance.PolicyExceptionScopeType;
import io.github.aiarchguard.platform.governance.PolicyExceptionView;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 有期限治理例外的应用用例：Maintainer 创建/撤销，Viewer 读取，均先检查项目和资源边界。
 * 例外独立于扫描人工处置与基线分类；写入例外及撤销事实不会改写 Finding 或既有门禁。
 */
@Service
class PolicyExceptionApplicationService implements PolicyExceptionOperations {
    private static final Duration MAX_LIFETIME = Duration.ofDays(30);
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final CurrentActorProvider actors;
    private final PolicyExceptionStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;

    PolicyExceptionApplicationService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, CurrentActorProvider actors, PolicyExceptionStore store,
            AuditRecorder audit, TraceIdProvider traceIds, Clock clock) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.actors = actors; this.store = store; this.audit = audit; this.traceIds = traceIds; this.clock = clock;
    }

    /**
     * 创建限定到单个指纹或规则的例外并同事务审计；有效期最长为生效起 30 天，且到期必须在未来。
     * 可以创建尚未生效的例外，是否影响某次门禁由该次求值时间决定。
     */
    @Override @Transactional
    public PolicyExceptionView create(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId, PolicyExceptionScopeType scopeType, String scopeValue,
            String reason, Instant effectiveAt, Instant expiresAt) {
        projects.requireMaintainer(projectId);
        String branch = GovernanceScopeInput.branch(targetBranch);
        requireScope(projectId, repositoryId, ruleSetVersionId);
        String normalizedReason = reason(reason);
        // 不允许空范围或隐式全局通配，范围还会被 Project/Repository/分支/规则版本共同约束。
        if (scopeType == null || scopeValue == null || !validScope(scopeType, scopeValue)) {
            throw new InvalidGovernanceInputException("Exception scope must be a single Finding fingerprint or rule ID");
        }
        if (effectiveAt == null || expiresAt == null || !expiresAt.isAfter(effectiveAt)
            || Duration.between(effectiveAt, expiresAt).compareTo(MAX_LIFETIME) > 0
            || !expiresAt.isAfter(Instant.now(clock))) {
            throw new InvalidGovernanceInputException("Exception expiry must be in the future and within 30 days of effect");
        }
        Instant now = Instant.now(clock);
        UUID actor = actors.currentActor().id();
        PolicyExceptionRecord value = new PolicyExceptionRecord(UUID.randomUUID(), projectId, repositoryId,
            branch, ruleSetVersionId, scopeType, scopeValue, normalizedReason, effectiveAt, expiresAt,
            actor, now, null, null, null, null);
        store.insert(value);
        audit.record(new AuditEvent(actor, projectId, "governance.policy_exception.create", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("exceptionId", value.id(), "scopeType", scopeType.name(),
                "expiresAt", expiresAt.toString())));
        return value.viewAt(now);
    }

    /** 追加一次撤销版本与审计；同原因重放复用结果，不同原因不能覆盖原撤销事实。 */
    @Override @Transactional
    public PolicyExceptionView revoke(UUID projectId, UUID repositoryId, UUID exceptionId, String reason) {
        projects.requireMaintainer(projectId);
        repositories.get(projectId, repositoryId);
        String normalizedReason = reason(reason);
        PolicyExceptionRecord value = store.find(projectId, repositoryId, exceptionId)
            .orElseThrow(PolicyExceptionNotFoundException::new);
        if (value.revocationVersionId() != null) {
            if (!normalizedReason.equals(value.revocationReason())) {
                throw new GovernanceConflictException("Exception was revoked with a different reason");
            }
            return value.viewAt(Instant.now(clock));
        }
        UUID actor = actors.currentActor().id();
        Instant now = Instant.now(clock);
        // 撤销唯一约束处理并发胜者，重新读取后检查原因；仅真正新增时记一次成功审计。
        boolean inserted = store.revoke(exceptionId, UUID.randomUUID(), normalizedReason, actor, now);
        PolicyExceptionRecord current = store.find(projectId, repositoryId, exceptionId).orElseThrow();
        if (!inserted && !normalizedReason.equals(current.revocationReason())) {
            throw new GovernanceConflictException("Exception was revoked with a different reason");
        }
        if (inserted) audit.record(new AuditEvent(actor, projectId, "governance.policy_exception.revoke",
            AuditResult.SUCCESS, traceIds.currentTraceId(), Map.of("exceptionId", exceptionId,
                "versionId", current.revocationVersionId())));
        return current.viewAt(Instant.now(clock));
    }

    /** 读取同一空间全部例外，并用一次服务器时间投影状态；到期不是一次数据库更新。 */
    @Override public List<PolicyExceptionView> list(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId) {
        projects.requireViewer(projectId);
        String branch = GovernanceScopeInput.branch(targetBranch);
        requireScope(projectId, repositoryId, ruleSetVersionId);
        Instant now = Instant.now(clock);
        return store.list(projectId, repositoryId, branch, ruleSetVersionId).stream()
            .map(value -> value.viewAt(now)).toList();
    }
    /** Viewer 按项目和仓库边界读取一个例外及其当前时间状态。 */
    @Override public PolicyExceptionView get(UUID projectId, UUID repositoryId, UUID exceptionId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return store.find(projectId, repositoryId, exceptionId)
            .orElseThrow(PolicyExceptionNotFoundException::new).viewAt(Instant.now(clock));
    }
    private void requireScope(UUID projectId, UUID repositoryId, UUID versionId) {
        repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, versionId);
    }
    private static boolean validScope(PolicyExceptionScopeType type, String value) {
        return type == PolicyExceptionScopeType.FINGERPRINT
            ? value.matches("[0-9a-f]{64}")
            : value.matches("[A-Za-z][A-Za-z0-9_.-]{0,159}");
    }
    private static String reason(String value) {
        if (value == null || value.length() > 1000 || value.trim().length() < 10) {
            throw new InvalidGovernanceInputException("Exception reason must contain 10–1000 characters");
        }
        return value.trim();
    }
}
