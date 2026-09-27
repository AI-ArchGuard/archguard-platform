package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GithubWebhookDisposition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GithubWebhookStore {
    record Delivery(String payloadSha256, GithubWebhookDisposition disposition) { }
    Optional<Delivery> delivery(UUID id);
    boolean insertDelivery(UUID id, String payloadSha256, String eventType, String action,
                           String externalRepositoryId, UUID projectId, UUID repositoryId,
                           Instant eventAt, Instant processedAt);
    void finishDelivery(UUID id, GithubWebhookDisposition disposition);
    boolean applyPullRequest(GithubPullRequestView value, UUID deliveryId, Instant processedAt);
    void recordAppliedHead(GithubPullRequestView value, UUID deliveryId);
    Optional<String> previousDistinctHead(UUID projectId, UUID repositoryId, String externalId,
                                          String targetBranch, String currentHeadSha, Instant currentEventAt);
    Optional<GithubPullRequestView> pullRequest(UUID projectId, UUID repositoryId, String externalId);
    List<GithubPullRequestView> listPullRequests(UUID projectId, UUID repositoryId, long offset, int limit);
    boolean attachGateIfCurrent(UUID projectId, UUID repositoryId, String externalId,
                                String headSha, UUID gateId);
}
