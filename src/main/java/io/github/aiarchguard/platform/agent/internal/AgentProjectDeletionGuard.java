package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class AgentProjectDeletionGuard implements ProjectDeletionGuard {
    private final AgentStore store;
    private final AgentSchemaAvailability schema;

    AgentProjectDeletionGuard(AgentStore store, AgentSchemaAvailability schema) {
        this.store = store; this.schema = schema;
    }

    @Override public boolean hasContent(UUID projectId) { return !schema.ready() || store.hasRequests(projectId); }
}
