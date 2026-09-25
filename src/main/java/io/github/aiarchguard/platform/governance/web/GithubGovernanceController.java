package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.governance.GithubGovernanceOperations;
import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GithubRepositoryLinkView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/github")
final class GithubGovernanceController {
    private final GithubGovernanceOperations github;
    GithubGovernanceController(GithubGovernanceOperations github) { this.github = github; }

    @PutMapping("/link")
    GithubRepositoryLinkView link(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @Valid @RequestBody LinkRequest request) {
        return github.link(projectId, repositoryId, request.providerRepositoryId(),
            request.ownerName(), request.repositoryName());
    }
    @GetMapping("/link")
    GithubRepositoryLinkView link(@PathVariable UUID projectId, @PathVariable UUID repositoryId) {
        return github.link(projectId, repositoryId);
    }
    @GetMapping("/pull-requests/{externalId}")
    GithubPullRequestView pullRequest(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable String externalId) {
        return github.pullRequest(projectId, repositoryId, externalId);
    }
    record LinkRequest(@NotBlank String providerRepositoryId, @NotBlank String ownerName,
                       @NotBlank String repositoryName) { }
}
