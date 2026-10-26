package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GithubRepositoryLinkView;
import java.util.Optional;
import java.util.UUID;

/**
 * GitHub 数字仓库身份与 Platform 仓库绑定的内部存储端口，供授权用例和验签 Webhook 定位范围。
 * byExternalId 是适配器内部路由查询，不是给普通调用者绕过 Project 授权的公开应用接口。
 */
public interface GithubRepositoryStore {
    Optional<GithubRepositoryLinkView> find(UUID projectId, UUID repositoryId);
    Optional<GithubRepositoryLinkView> byExternalId(String externalId);
    /** 首次绑定成功返回 true，仓库或外部身份已占用时返回 false，由上层判定重放或冲突。 */
    boolean insert(GithubRepositoryLinkView value);
}
