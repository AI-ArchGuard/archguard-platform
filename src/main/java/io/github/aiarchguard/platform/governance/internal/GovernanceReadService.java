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
    @Override public ComparisonView comparison(UUID projectId, UUID repositoryId, UUID comparisonId) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return baselines.findComparison(projectId, repositoryId, comparisonId)
            .orElseThrow(ComparisonNotFoundException::new);
    }
    @Override public GovernancePage<GithubPullRequestView> pullRequests(UUID projectId,
            UUID repositoryId, int page, int size) {
        projects.requireViewer(projectId);
        repositories.get(projectId, repositoryId);
        return GovernancePage.of(github.listPullRequests(projectId, repositoryId,
            offset(page, size), size + 1), page, size);
    }
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
        if (currentCandidate == null || !currentCandidate.gateId().equals(current.currentGateEvaluationId())) {
            return unavailable("CURRENT_REPORT_MISSING", current, previousHead, ruleSetVersionId, null, null);
        }
        var previousCandidate = revisionDeltas.candidate(projectId, repositoryId, prId,
            previousHead, current.targetBranch(), ruleSetVersionId).orElse(null);
        if (previousCandidate == null) {
            return unavailable("PREVIOUS_REPORT_MISSING", current, previousHead, ruleSetVersionId,
                currentCandidate.gateId(), null);
        }
        if (!currentCandidate.fingerprintVersion().equals(previousCandidate.fingerprintVersion())) {
            return unavailable("INCOMPATIBLE", current, previousHead, ruleSetVersionId,
                currentCandidate.gateId(), previousCandidate.gateId());
        }
        ComparisonView currentComparison = baselines.findComparison(projectId, repositoryId,
            currentCandidate.comparisonId()).orElseThrow();
        ComparisonView previousComparison = baselines.findComparison(projectId, repositoryId,
            previousCandidate.comparisonId()).orElseThrow();
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
    private static PrRevisionDeltaView unavailable(String reason, GithubPullRequestView current,
            String previousHead, UUID ruleSetVersionId, UUID currentGateId, UUID previousGateId) {
        return new PrRevisionDeltaView("PR_PREVIOUS_REVISION", reason, current.headSha(),
            previousHead, currentGateId, previousGateId, ruleSetVersionId, null, List.of());
    }
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
    private static ClassifiedFinding reclassify(ClassifiedFinding finding, Classification classification) {
        return new ClassifiedFinding(classification, finding.fingerprint(), finding.payloadSha256(),
            finding.ruleId(), finding.ruleVersion(), finding.severity(), finding.scannerFindingId());
    }
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
