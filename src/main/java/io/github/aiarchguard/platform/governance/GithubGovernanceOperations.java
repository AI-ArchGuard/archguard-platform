package io.github.aiarchguard.platform.governance;

import java.util.UUID;

public interface GithubGovernanceOperations {
    GithubRepositoryLinkView link(UUID projectId, UUID repositoryId, String providerRepositoryId,
                                  String ownerName, String repositoryName);
    GithubRepositoryLinkView link(UUID projectId, UUID repositoryId);
    GithubPullRequestView pullRequest(UUID projectId, UUID repositoryId, String externalId);
    GithubWebhookResult receive(UUID deliveryId, String eventType, String signature, byte[] payload);
}
