package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.governance.ComparisonView;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GovernancePage;
import io.github.aiarchguard.platform.governance.GovernanceReadOperations;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}")
final class GovernanceReadController {
    private final GovernanceReadOperations reads;
    GovernanceReadController(GovernanceReadOperations reads) { this.reads = reads; }

    @GetMapping("/gate-evaluations")
    GovernancePage<GateEvaluationView> gates(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @RequestParam String targetBranch, @RequestParam UUID ruleSetVersionId,
            @RequestParam(required = false) String pullRequestId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reads.gates(projectId, repositoryId, targetBranch, ruleSetVersionId, pullRequestId, page, size);
    }
    @GetMapping("/comparisons/{comparisonId}")
    ComparisonView comparison(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable UUID comparisonId) {
        return reads.comparison(projectId, repositoryId, comparisonId);
    }
    @GetMapping("/github/pull-requests")
    GovernancePage<GithubPullRequestView> pullRequests(@PathVariable UUID projectId,
            @PathVariable UUID repositoryId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reads.pullRequests(projectId, repositoryId, page, size);
    }
}
