package io.github.aiarchguard.platform.repository;

import java.util.UUID;

public interface RepositoryCatalog {
    RepositoryView requireRegistered(UUID projectId, UUID repositoryId);
}
