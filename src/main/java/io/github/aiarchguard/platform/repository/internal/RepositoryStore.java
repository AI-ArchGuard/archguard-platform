package io.github.aiarchguard.platform.repository.internal;

import io.github.aiarchguard.platform.repository.RepositoryView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryStore {
    void insert(RepositoryView repository);
    List<RepositoryView> list(UUID projectId);
    Optional<RepositoryView> find(UUID projectId, UUID repositoryId);
    boolean existsForProject(UUID projectId);
}
