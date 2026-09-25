package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GithubRepositoryLinkView;
import java.util.Optional;
import java.util.UUID;

public interface GithubRepositoryStore {
    Optional<GithubRepositoryLinkView> find(UUID projectId, UUID repositoryId);
    Optional<GithubRepositoryLinkView> byExternalId(String externalId);
    boolean insert(GithubRepositoryLinkView value);
}
