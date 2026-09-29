package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class AgentProjectDeletionGuard implements ProjectDeletionGuard {
    private final AgentStore store;

    AgentProjectDeletionGuard(AgentStore store) { this.store = store; }

    @Override public boolean hasContent(UUID projectId) { return store.hasRequests(projectId); }
}
