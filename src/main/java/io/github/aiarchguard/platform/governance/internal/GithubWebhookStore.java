package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GithubWebhookDisposition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 已验签 Webhook 的 delivery、当前 PR head 与实际应用修订史的内部端口。
 * 调用方负责验签和事务；本端口的数据库条件更新只保证已知事件时间顺序，不推导 Git 拓扑。
 */
public interface GithubWebhookStore {
    /** 重放比对所需的最小事实，不保留或返回 Webhook 正文。 */
    record Delivery(String payloadSha256, GithubWebhookDisposition disposition) { }
    Optional<Delivery> delivery(UUID id);
    boolean insertDelivery(UUID id, String payloadSha256, String eventType, String action,
                           String externalRepositoryId, UUID projectId, UUID repositoryId,
                           Instant eventAt, Instant processedAt);
    /** 将新 delivery 的最终处置封存，后续重放直接复用。 */
    void finishDelivery(UUID id, GithubWebhookDisposition disposition);
    /** 仅首次 head 或严格更新的事件时间可应用；head 变化会清空旧的当前门禁指针。 */
    boolean applyPullRequest(GithubPullRequestView value, UUID deliveryId, Instant processedAt);
    /** 只在 applyPullRequest 成功之后追加签名修订历史，不能为忽略或迟到事件调用。 */
    void recordAppliedHead(GithubPullRequestView value, UUID deliveryId);
    /** 同 PR/分支中，取时间早于当前且 head 不同的最近实际应用修订，跳过同 head 重复事件。 */
    Optional<String> previousDistinctHead(UUID projectId, UUID repositoryId, String externalId,
                                          String targetBranch, String currentHeadSha, Instant currentEventAt);
    Optional<GithubPullRequestView> pullRequest(UUID projectId, UUID repositoryId, String externalId);
    List<GithubPullRequestView> listPullRequests(UUID projectId, UUID repositoryId, long offset, int limit);
    /** head 必须仍相同，且门禁不早于当前所挂门禁；用于抵御迟到报告和并发状态覆盖。 */
    boolean attachGateIfCurrent(UUID projectId, UUID repositoryId, String externalId,
                                String headSha, UUID gateId);
}
