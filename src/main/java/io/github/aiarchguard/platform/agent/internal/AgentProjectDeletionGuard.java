package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 项目删除前检查 Agent 请求和个人确认历史；Agent Schema 不可用时保守阻止删除，避免不可检查的关联数据被绕过。
 */
@Component
class AgentProjectDeletionGuard implements ProjectDeletionGuard {
    private final AgentStore store;
    private final AgentSchemaAvailability schema;
    private final AgentEnablementStore enablements;

    AgentProjectDeletionGuard(AgentStore store, AgentSchemaAvailability schema, AgentEnablementStore enablements) {
        this.store = store; this.schema = schema; this.enablements = enablements;
    }

    @Override public boolean hasContent(UUID projectId) { return !schema.ready() || store.hasRequests(projectId) || enablements.hasHistory(projectId); }
}
