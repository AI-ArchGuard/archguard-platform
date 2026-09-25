package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GithubWebhookDisposition;
import java.time.Instant;
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
    Optional<GithubPullRequestView> pullRequest(UUID projectId, UUID repositoryId, String externalId);
    boolean attachGateIfCurrent(UUID projectId, UUID repositoryId, String externalId,
                                String headSha, UUID gateId);
}
