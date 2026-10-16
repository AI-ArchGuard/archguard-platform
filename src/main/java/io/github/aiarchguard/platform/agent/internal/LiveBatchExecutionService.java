package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.LiveBatchExecutionOperations;
import io.github.aiarchguard.platform.agent.LiveBatchExecutionView;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Historical observation only: no credential, inventory, admission or model dependency. */
@Service
final class LiveBatchExecutionService implements LiveBatchExecutionOperations {
    private final ProjectAuthorization projects;
    private final AgentSchemaAvailability schema;
    private final LiveBatchExecutionStore store;

    LiveBatchExecutionService(ProjectAuthorization projects, AgentSchemaAvailability schema,
            LiveBatchExecutionStore store) {
        this.projects = projects;
        this.schema = schema;
        this.store = store;
    }

    @Override public LiveBatchExecutionView get(UUID projectId, UUID batchId) {
        projects.requireViewer(projectId);
        schema.requireReady();
        return store.load(projectId, batchId).orElseThrow(AgentNotFoundException::new);
    }
}
