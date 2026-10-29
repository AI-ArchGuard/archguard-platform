package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GateEvaluationOperations;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GithubLinkNotFoundException;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.GovernanceScopeInput;
import io.github.aiarchguard.platform.governance.ReportSubmissionMetadata;
import io.github.aiarchguard.platform.governance.ReportSubmissionNotFoundException;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionStatus;
import io.github.aiarchguard.platform.governance.ReportSubmissionView;
import io.github.aiarchguard.platform.governance.SubmissionGateView;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * CI 报告提交的应用编排：校验授权与输入，复用接收记录，再求值门禁并完成关联。
 * 接收导入、门禁求值和完成关联由不同服务分段事务化；整个 submit 不是一个原子事务，也未派发后台 worker。
 */
@Service
class ReportSubmissionApplicationService implements ReportSubmissionOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final GithubRepositoryStore links;
    private final ReportSubmissionStore store;
    private final ReportSubmissionWriteService writer;
    private final ReportSubmissionFinalizeService finalizer;
    private final GateEvaluationOperations gates;
    private final CurrentActorProvider actors;

    ReportSubmissionApplicationService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, GithubRepositoryStore links, ReportSubmissionStore store,
            ReportSubmissionWriteService writer, ReportSubmissionFinalizeService finalizer,
            GateEvaluationOperations gates, CurrentActorProvider actors) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.links = links; this.store = store; this.writer = writer; this.finalizer = finalizer;
        this.gates = gates; this.actors = actors;
    }

    /**
     * Maintainer 提交原始报告及修订断言；相同 Project 中同键同摘要或不同键同摘要复用原提交。
     * 本次调用会继续处理 RECEIVED，响应中的 replay 只表示是否复用提交，不代表本次没有恢复工作。
     */
    @Override public Acceptance submit(UUID projectId, UUID repositoryId, String idempotencyKey,
            ReportSubmissionMetadata rawMetadata, byte[] report) {
        projects.requireMaintainer(projectId);
        String key = GovernanceScopeInput.idempotencyKey(idempotencyKey);
        repositories.get(projectId, repositoryId);
        ReportSubmissionMetadata metadata = SubmissionContract.validate(rawMetadata, report);
        ruleSets.requireVersion(projectId, repositoryId, metadata.ruleSetVersionId());
        // 数字仓库身份必须匹配已有绑定；CI 提交本身不能更新 Webhook 维护的当前 PR head。
        var link = links.find(projectId, repositoryId).orElseThrow(GithubLinkNotFoundException::new);
        if (!link.providerRepositoryId().equals(metadata.revision().providerRepositoryId())) {
            throw new GovernanceConflictException("Report GitHub repository identity does not match the registered Repository");
        }
        String digest = SubmissionContract.digest(projectId, repositoryId, metadata);
        var byKey = store.byKey(projectId, key);
        if (byKey.isPresent() && !byKey.get().view().requestDigest().equals(digest)) {
            throw new GovernanceConflictException("Idempotency-Key is bound to different report inputs");
        }
        ReportSubmissionStore.Stored existing = byKey.or(() -> store.byDigest(projectId, digest)).orElse(null);
        Acceptance accepted = existing == null
            ? writer.accept(projectId, repositoryId, key, digest, metadata, report)
            : new Acceptance(existing.view(), true);
        ReportSubmissionView submitted = accepted.submission();
        if (submitted.status() == ReportSubmissionStatus.RECEIVED) {
            // 接收事务已经提交；这里或完成阶段失败时保留原 RECEIVED，客户端同键重放继续处理。
            // 稳定的 submission:ID 门禁键也复用已完成求值，避免恢复时重新采用新的基线/例外。
            ReportSubmissionStore.Stored stored = store.find(projectId, repositoryId, submitted.id()).orElseThrow();
            GateEvaluationView gate = gates.evaluate(projectId, repositoryId, submitted.revision().targetBranch(),
                submitted.ruleSetVersionId(), stored.scanJobId(), "submission:" + submitted.id());
            finalizer.complete(stored, gate, actors.currentActor().id());
            submitted = store.find(projectId, repositoryId, submitted.id()).orElseThrow().view();
        }
        // HTTP 层据首次/重放选择 202/200；首次返回 202 并不表示此处存在异步调度器。
        return new Acceptance(submitted, accepted.replay());
    }

    /** Viewer 读取提交生命周期；只读查询不会主动恢复尚未完成的提交。 */
    @Override public ReportSubmissionView get(UUID projectId, UUID repositoryId, UUID submissionId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return store.find(projectId, repositoryId, submissionId)
            .orElseThrow(ReportSubmissionNotFoundException::new).view();
    }
    /** 只为已完成提交组合其确切门禁与修订信息；处理中不把缺失结果推导为 PASS。 */
    @Override public SubmissionGateView gate(UUID projectId, UUID repositoryId, UUID submissionId) {
        ReportSubmissionView submission = get(projectId, repositoryId, submissionId);
        if (submission.status() != ReportSubmissionStatus.COMPLETED || submission.gateEvaluationId() == null) {
            throw new GovernanceConflictException("Report submission has not completed gate evaluation");
        }
        GateEvaluationView value = gates.get(projectId, repositoryId, submission.gateEvaluationId());
        return new SubmissionGateView(value.id(), submissionId, projectId, repositoryId,
            submission.ruleSetVersionId(), submission.revision(), value.baselineVersionId(),
            value.outcome(), value.errorKind(), value.errorCode(), value.ciExitCode(),
            value.policyVersion(), value.fingerprintVersion(),
            Map.of("NEW", value.newCount(), "EXISTING", value.existingCount(), "RESOLVED", value.resolvedCount()),
            value.matchedExceptionVersionIds(), value.evaluatedAt());
    }

    /** 授权当前 Viewer 后，检查扫描、原始报告摘要与一个已记录 PR 修订的完成关联。 */
    @Override public boolean matchesCompletedPrRevision(UUID projectId, UUID scanJobId,
            String reportSha256, UUID prHeadRevisionId) {
        projects.requireViewer(projectId);
        return store.matchesCompletedPrRevision(projectId, scanJobId, reportSha256, prHeadRevisionId);
    }

    /** 为显式主体重新验证 Viewer 权限；匹配的是指定修订事实，不保证它仍是 PR 当前 head。 */
    @Override public boolean matchesCompletedPrRevisionForActor(UUID projectId, UUID actorId,
            UUID scanJobId, String reportSha256, UUID prHeadRevisionId) {
        projects.requireViewerForActor(projectId, actorId);
        return store.matchesCompletedPrRevision(projectId, scanJobId, reportSha256, prHeadRevisionId);
    }
}
