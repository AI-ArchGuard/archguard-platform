package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.ComparisonNotFoundException;
import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GovernancePage;
import io.github.aiarchguard.platform.governance.GovernanceReadOperations;
import io.github.aiarchguard.platform.governance.GovernanceScopeInput;
import io.github.aiarchguard.platform.governance.InvalidGovernanceInputException;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class GovernanceReadService implements GovernanceReadOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final GateEvaluationStore gates;
    private final BaselineStore baselines;
    private final GithubWebhookStore github;

    GovernanceReadService(ProjectAuthorization projects, RepositoryOperations repositories,
            RuleSetCatalog ruleSets, GateEvaluationStore gates, BaselineStore baselines,
            GithubWebhookStore github) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets;
        this.gates = gates; this.baselines = baselines; this.github = github;
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
