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

/**
 * 需要当前用户 Project 权限的 GitHub 管理/查询 HTTP 入口，复用 GithubGovernanceOperations。
 * 这里只登记外部稳定身份并读取已知 PR 状态；Webhook 的 HMAC 接收入口由独立控制器提供。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/github")
final class GithubGovernanceController {
    private final GithubGovernanceOperations github;
    GithubGovernanceController(GithubGovernanceOperations github) { this.github = github; }

    /** Maintainer 建立绑定；同稳定身份可重放，已有绑定不能借此改成另一 GitHub 仓库。 */
    @PutMapping("/link")
    GithubRepositoryLinkView link(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @Valid @RequestBody LinkRequest request) {
        return github.link(projectId, repositoryId, request.providerRepositoryId(),
            request.ownerName(), request.repositoryName());
    }
    /** Viewer 读取登记的外部身份，不向 GitHub 拉取仓库信息。 */
    @GetMapping("/link")
    GithubRepositoryLinkView link(@PathVariable UUID projectId, @PathVariable UUID repositoryId) {
        return github.link(projectId, repositoryId);
    }
    /** 读取验签事件建立的当前 head 和门禁指针；没有门禁指针不能显示为当前 PASS。 */
    @GetMapping("/pull-requests/{externalId}")
    GithubPullRequestView pullRequest(@PathVariable UUID projectId, @PathVariable UUID repositoryId,
            @PathVariable String externalId) {
        return github.pullRequest(projectId, repositoryId, externalId);
    }
    /**
     * providerRepositoryId 是稳定数字身份；ownerName/repositoryName 为展示名，不是访问凭据。
     * Bean Validation 只检查非空，数字格式、名称格式及既有绑定冲突由应用层继续检查。
     */
    record LinkRequest(@NotBlank String providerRepositoryId, @NotBlank String ownerName,
                       @NotBlank String repositoryName) { }
}
