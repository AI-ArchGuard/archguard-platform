package io.github.aiarchguard.platform.governance;

import java.util.UUID;

public interface GovernanceReadOperations {
    GovernancePage<GateEvaluationView> gates(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId, String pullRequestId, int page, int size);
    ComparisonView comparison(UUID projectId, UUID repositoryId, UUID comparisonId);
    GovernancePage<GithubPullRequestView> pullRequests(UUID projectId, UUID repositoryId, int page, int size);
}
