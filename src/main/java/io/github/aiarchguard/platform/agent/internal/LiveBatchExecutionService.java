package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.LiveBatchExecutionOperations;
import io.github.aiarchguard.platform.agent.LiveBatchExecutionView;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 只读执行视图的应用服务：先核验当前项目 Viewer 与 Schema，再读持久化快照；不依赖凭据、来源清单、发送准入或模型。
 */
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
