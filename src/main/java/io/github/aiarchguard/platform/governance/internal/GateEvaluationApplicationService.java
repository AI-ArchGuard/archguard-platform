package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.BaselineNotFoundException;
import io.github.aiarchguard.platform.governance.BaselineOperations;
import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.GateErrorKind;
import io.github.aiarchguard.platform.governance.GateEvaluationNotFoundException;
import io.github.aiarchguard.platform.governance.GateEvaluationOperations;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GateOutcome;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.GovernanceScopeInput;
import io.github.aiarchguard.platform.governance.InvalidGovernanceReportException;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobConflictException;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 门禁应用编排：授权并核对候选后，读取/创建不可变比较，以当前有效例外计算默认策略。
 * 自身不包围整个流程开启事务；比较事务结束后，再由独立写服务保存 PASS、FAIL 或可识别的 ERROR。
 */
@Service
class GateEvaluationApplicationService implements GateEvaluationOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final ScanJobOperations jobs;
    private final BaselineOperations baselines;
    private final PolicyExceptionStore exceptions;
    private final GateEvaluationStore store;
    private final GateWriteService writer;
    private final CurrentActorProvider actors;
    private final Clock clock;

    GateEvaluationApplicationService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, ScanJobOperations jobs, BaselineOperations baselines,
            PolicyExceptionStore exceptions, GateEvaluationStore store, GateWriteService writer,
            CurrentActorProvider actors, Clock clock) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.jobs = jobs; this.baselines = baselines; this.exceptions = exceptions; this.store = store;
        this.writer = writer; this.actors = actors; this.clock = clock;
    }

    /**
     * Maintainer 发起一次门禁；同一 Project 的键与输入摘要相同就复用历史结果，重评需新键。
     * 先做授权与归属检查，再识别已封存结果，避免用幂等查询绕过访问边界。
     */
    @Override public GateEvaluationView evaluate(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId, UUID candidateJobId, String idempotencyKey) {
        projects.requireMaintainer(projectId);
        String branch = GovernanceScopeInput.branch(targetBranch);
        String key = GovernanceScopeInput.idempotencyKey(idempotencyKey);
        repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, ruleSetVersionId);
        var job = jobs.get(projectId, candidateJobId);
        if (!job.repositoryId().equals(repositoryId) || !job.ruleSetVersionId().equals(ruleSetVersionId)) {
            throw new GovernanceConflictException("Scan job belongs to another repository or RuleSetVersion");
        }
        String digest = digest(projectId, repositoryId, branch, ruleSetVersionId, candidateJobId);
        var previous = store.byKey(projectId, key);
        if (previous.isPresent()) {
            // 不把当前基线或例外加入摘要，重放必须返回原求值，而不是悄悄用新配置重算。
            if (!previous.get().requestSha256().equals(digest)) {
                throw new GovernanceConflictException("Idempotency-Key is bound to different gate inputs");
            }
            return previous.get().view();
        }
        UUID actor = actors.currentActor().id();
        Instant at = Instant.now(clock);
        GateEvaluationView evaluation;
        if (job.status() != ScanJobStatus.SUCCEEDED) {
            evaluation = error(projectId, repositoryId, branch, ruleSetVersionId, candidateJobId,
                null, null, GateErrorKind.EXECUTION_OR_CONTRACT, "scan_job_not_successful", 70, at);
        } else {
            try {
                ComparisonView comparison = baselines.compare(projectId, repositoryId, branch,
                    ruleSetVersionId, candidateJobId);
                Instant decisionAt = Instant.now(clock);
                GateDecision decision = GatePolicy.evaluate(comparison,
                    exceptions.list(projectId, repositoryId, branch, ruleSetVersionId), decisionAt);
                evaluation = new GateEvaluationView(UUID.randomUUID(), projectId, repositoryId, branch,
                    ruleSetVersionId, candidateJobId, comparison.id(), comparison.baselineVersionId(), decision.outcome(),
                    decision.ciExitCode(), null, null, GatePolicy.VERSION, comparison.fingerprintVersion(),
                    comparison.newCount(), comparison.existingCount(), comparison.resolvedCount(),
                    decision.blockedCount(), decision.matchedExceptionVersionIds(), decisionAt);
            } catch (BaselineNotFoundException ex) {
                // 缺少兼容基线属于配置错误，不能把基线当空集合后得到 PASS。
                evaluation = error(projectId, repositoryId, branch, ruleSetVersionId, candidateJobId,
                    null, null, GateErrorKind.CONFIGURATION, "baseline_not_found", 64, at);
            } catch (InvalidGovernanceReportException | GovernanceConflictException | ScanJobConflictException ex) {
                // 仅把列出的比较/契约失败转成 ERROR；其他运行时失败继续传播，不伪造成功结果。
                evaluation = error(projectId, repositoryId, branch, ruleSetVersionId, candidateJobId,
                    null, null, GateErrorKind.EXECUTION_OR_CONTRACT, "comparison_failed", 70, at);
            }
        }
        // 比较可能已回滚；通过另一个服务的事务落库，避免已标记回滚的事务吞掉 ERROR 历史。
        return writer.persist(evaluation, key, digest, actor);
    }

    /** Viewer 读取确切历史门禁，不按当前活动基线或例外重新求值。 */
    @Override public GateEvaluationView get(UUID projectId, UUID repositoryId, UUID evaluationId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return store.find(projectId, repositoryId, evaluationId)
            .orElseThrow(GateEvaluationNotFoundException::new);
    }

    private static GateEvaluationView error(UUID projectId, UUID repositoryId, String branch, UUID rules, UUID job,
            UUID comparison, UUID baseline, GateErrorKind kind, String code, int exitCode, Instant at) {
        return new GateEvaluationView(UUID.randomUUID(), projectId, repositoryId, branch, rules, job,
            comparison, baseline, GateOutcome.ERROR, exitCode, kind, code, GatePolicy.VERSION,
            ReportFingerprintComputer.VERSION, 0, 0, 0, 0, List.of(), at);
    }
    /**
     * 门禁专用摘要仅绑定五项输入，末尾无额外换行；它不同于带版本前缀、报告 SHA 的提交摘要契约。
     */
    private static String digest(UUID project, UUID repository, String branch, UUID rules, UUID job) {
        try {
            byte[] bytes = (project + "\n" + repository + "\n" + branch + "\n" + rules + "\n" + job)
                .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
