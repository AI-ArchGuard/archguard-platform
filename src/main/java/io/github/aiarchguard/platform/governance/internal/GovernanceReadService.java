package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.ComparisonNotFoundException;
import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.Classification;
import io.github.aiarchguard.platform.governance.ClassifiedFinding;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GithubPullRequestNotFoundException;
import io.github.aiarchguard.platform.governance.GovernancePage;
import io.github.aiarchguard.platform.governance.GovernanceReadOperations;
import io.github.aiarchguard.platform.governance.GovernanceScopeInput;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.governance.PrRevisionDeltaView;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 治理查询应用服务，为 Viewer 组合不可变比较、门禁历史和已验签 PR head 的只读视图。
 * PR 修订差异是独立展示参考点，不修改基线分类、门禁、例外或 CI 退出码。
 */
@Service
class GovernanceReadService implements GovernanceReadOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final GateEvaluationStore gates;
    private final BaselineStore baselines;
    private final GithubWebhookStore github;
    private final PrRevisionDeltaStore revisionDeltas;

    GovernanceReadService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, GateEvaluationStore gates, BaselineStore baselines,
            GithubWebhookStore github, PrRevisionDeltaStore revisionDeltas) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.gates = gates; this.baselines = baselines; this.github = github;
        this.revisionDeltas = revisionDeltas;
    }

    /** 在明确分支与规则版本内查询门禁历史；多取一条推导 hasMore，不跨比较空间混排。 */
    @Override public GovernancePage<GateEvaluationView> gates(UUID projectId, UUID repositoryId,
            String targetBranch, UUID ruleSetVersionId, String pullRequestId, int page, int size) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, ruleSetVersionId);
        String branch = GovernanceScopeInput.branch(targetBranch);
        long offset = offset(page, size);
        String pr = pullRequestId == null ? null : numericId(pullRequestId);
        return GovernancePage.of(gates.list(projectId, repositoryId, branch, ruleSetVersionId,
            pr, offset, size + 1), page, size);
    }
    /** 按授权范围读取已冻结比较，保留求值时的确切基线与分类，不重新计算历史。 */
    @Override public ComparisonView comparison(UUID projectId, UUID repositoryId, UUID comparisonId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return baselines.findComparison(projectId, repositoryId, comparisonId)
            .orElseThrow(ComparisonNotFoundException::new);
    }
    /** 列出本仓库已接收的 PR head；空当前门禁指针表示该 head 尚未关联结果。 */
    @Override public GovernancePage<GithubPullRequestView> pullRequests(UUID projectId,
            UUID repositoryId, int page, int size) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return GovernancePage.of(github.listPullRequests(projectId, repositoryId,
            offset(page, size), size + 1), page, size);
    }
    /**
     * 在同一可重复读快照中比较当前 head 与前一个已验签、实际应用且不同的 head 的完整 Finding 集。
     * 前驱依据签名事件历史，不能按报告到达时间猜测；缺失/不兼容返回原因，不当作空集合。
     */
    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PrRevisionDeltaView prRevisionDelta(UUID projectId, UUID repositoryId,
            String externalId, UUID ruleSetVersionId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, ruleSetVersionId);
        String prId = numericId(externalId);
        GithubPullRequestView current = github.pullRequest(projectId, repositoryId, prId)
            .orElseThrow(GithubPullRequestNotFoundException::new);
        String previousHead = github.previousDistinctHead(projectId, repositoryId, prId,
            current.targetBranch(), current.headSha(), current.eventAt()).orElse(null);
        if (previousHead == null) {
            return unavailable("NO_PREVIOUS_REVISION", current, null, ruleSetVersionId, null, null);
        }
        var currentCandidate = revisionDeltas.candidate(projectId, repositoryId, prId,
            current.headSha(), current.targetBranch(), ruleSetVersionId).orElse(null);
        // 候选须有完成报告及比较，且门禁确为当前 head 指针，避免借用同 PR 的旧 PASS。
        if (currentCandidate == null || !currentCandidate.gateId().equals(current.currentGateEvaluationId())) {
            return unavailable("CURRENT_REPORT_MISSING", current, previousHead, ruleSetVersionId, null, null);
        }
        var previousCandidate = revisionDeltas.candidate(projectId, repositoryId, prId,
            previousHead, current.targetBranch(), ruleSetVersionId).orElse(null);
        if (previousCandidate == null) {
            return unavailable("PREVIOUS_REPORT_MISSING", current, previousHead, ruleSetVersionId,
                currentCandidate.gateId(), null);
        }
        // 两次查询已限定同规则版本；再要求算法版本一致，不能直接比较不同指纹空间。
        if (!currentCandidate.fingerprintVersion().equals(previousCandidate.fingerprintVersion())) {
            return unavailable("INCOMPATIBLE", current, previousHead, ruleSetVersionId,
                currentCandidate.gateId(), previousCandidate.gateId());
        }
        ComparisonView currentComparison = baselines.findComparison(projectId, repositoryId,
            currentCandidate.comparisonId()).orElseThrow();
        ComparisonView previousComparison = baselines.findComparison(projectId, repositoryId,
            previousCandidate.comparisonId()).orElseThrow();
        // 先从各自基线比较恢复完整候选集，再做 C−P/C∩P/P−C；不复用基线 RESOLVED 计数。
        Map<String, ClassifiedFinding> previous = candidateFindings(previousComparison);
        Map<String, ClassifiedFinding> candidate = candidateFindings(currentComparison);
        List<ClassifiedFinding> findings = new ArrayList<>();
        candidate.forEach((fingerprint, finding) -> findings.add(reclassify(finding,
            previous.containsKey(fingerprint) ? Classification.EXISTING : Classification.NEW)));
        previous.forEach((fingerprint, finding) -> {
            if (!candidate.containsKey(fingerprint)) findings.add(reclassify(finding, Classification.RESOLVED));
        });
        findings.sort(Comparator.comparing((ClassifiedFinding f) -> f.classification().name())
            .thenComparing(ClassifiedFinding::fingerprint));
        return new PrRevisionDeltaView("PR_PREVIOUS_REVISION", "AVAILABLE", current.headSha(),
            previousHead, currentCandidate.gateId(), previousCandidate.gateId(), ruleSetVersionId,
            currentCandidate.fingerprintVersion(), findings);
    }
    /** 空列表伴随明确不可用原因，仅表示不能比较，不表示两个修订均无问题。 */
    private static PrRevisionDeltaView unavailable(String reason, GithubPullRequestView current,
            String previousHead, UUID ruleSetVersionId, UUID currentGateId, UUID previousGateId) {
        return new PrRevisionDeltaView("PR_PREVIOUS_REVISION", reason, current.headSha(),
            previousHead, currentGateId, previousGateId, ruleSetVersionId, null, List.of());
    }
    /**
     * 基线比较中的 NEW 和 EXISTING 合起来才是该修订的完整候选集；RESOLVED 只存在于它的基线。
     * 对重复指纹显式报错，防止覆盖存储异常；这里不重新执行指纹计算或基线比较。
     */
    private static Map<String, ClassifiedFinding> candidateFindings(ComparisonView comparison) {
        Map<String, ClassifiedFinding> findings = new HashMap<>();
        for (ClassifiedFinding finding : comparison.findings()) {
            if (finding.classification() != Classification.RESOLVED) {
                if (findings.putIfAbsent(finding.fingerprint(), finding) != null) {
                    throw new IllegalStateException("Duplicate candidate fingerprint");
                }
            }
        }
        return findings;
    }
    /** 只为返回视图重标分类，保留原快照的载荷、严重性与扫描追溯 ID，无写入副作用。 */
    private static ClassifiedFinding reclassify(ClassifiedFinding finding, Classification classification) {
        return new ClassifiedFinding(classification, finding.fingerprint(), finding.payloadSha256(),
            finding.ruleId(), finding.ruleVersion(), finding.severity(), finding.scannerFindingId());
    }
    /** 页码从零开始，大小限制为 1–50；先提升到 long 再乘，避免 int 乘法溢出。 */
    private static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new InvalidGovernanceInputException("Page must be non-negative and size between 1 and 50");
        }
        return (long) page * size;
    }
    private static String numericId(String value) {
        if (!value.matches("[1-9][0-9]{0,19}")) {
            throw new InvalidGovernanceInputException("Pull request ID must be a positive GitHub number");
        }
        return value;
    }
}
