package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

public record GithubRepositoryLinkView(UUID projectId, UUID repositoryId, String provider,
                                       String providerRepositoryId, String ownerName, String repositoryName,
                                       UUID createdBy, Instant createdAt) { }
