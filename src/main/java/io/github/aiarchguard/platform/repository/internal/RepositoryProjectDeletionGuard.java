package io.github.aiarchguard.platform.repository.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class RepositoryProjectDeletionGuard implements ProjectDeletionGuard {
    private final RepositoryStore store;
    RepositoryProjectDeletionGuard(RepositoryStore store) { this.store=store; }
    @Override public boolean hasContent(UUID projectId) { return store.existsForProject(projectId); }
}
