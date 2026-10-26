package io.github.aiarchguard.platform.governance;

import java.util.UUID;

/**
 * GitHub 适配器的公开应用接口，把外部稳定仓库身份、已验证 PR head 和治理事实连接起来。
 * 普通管理/查询使用当前主体的 Project 权限；Webhook 接收使用 HMAC 信任边界，不走普通用户授权。
 * Platform 只消费元数据和报告，不通过这些方法 clone/fetch 或获取 Git 凭据。
 */
public interface GithubGovernanceOperations {
    /** Maintainer 创建稳定数字仓库 ID 绑定；同身份重放返回原绑定，改绑为另一身份则冲突。 */
    GithubRepositoryLinkView link(UUID projectId, UUID repositoryId, String providerRepositoryId,
                                  String ownerName, String repositoryName);
    /** Viewer 读取已登记的绑定，owner/name 只是展示名，不能替代 providerRepositoryId。 */
    GithubRepositoryLinkView link(UUID projectId, UUID repositoryId);
    /** Viewer 读取验签事件所建立的 PR head 及其当前门禁关联，缺少门禁表示尚无当前结论。 */
    GithubPullRequestView pullRequest(UUID projectId, UUID repositoryId, String externalId);
    /**
     * 校验原始正文 HMAC 后按 deliveryId 去重，再处理允许的 PR 事件、时间窗口和 head 顺序。
     * 相同 ID 不同正文冲突；验签事件可推进 head，但不证明 Git 祖先关系，旧事件不能覆盖新 head。
     */
    GithubWebhookResult receive(UUID deliveryId, String eventType, String signature, byte[] payload);
}
