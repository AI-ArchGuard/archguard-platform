package io.github.aiarchguard.platform.repository;

import java.util.List;
import java.util.UUID;

public interface RepositoryOperations {
    RepositoryView create(UUID projectId, String key, String name, String mountPath);
    List<RepositoryView> list(UUID projectId);
    RepositoryView get(UUID projectId, UUID repositoryId);
}
