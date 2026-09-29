package io.github.aiarchguard.platform.agentdocument.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class DocumentProjectDeletionGuard implements ProjectDeletionGuard {
    private final DocumentStore store;

    DocumentProjectDeletionGuard(DocumentStore store) { this.store = store; }

    @Override
    public boolean hasContent(UUID projectId) { return store.hasDocuments(projectId); }
}
