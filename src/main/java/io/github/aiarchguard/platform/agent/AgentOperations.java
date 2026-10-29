package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** 合成解释与 PR 摘要的应用接口：创建持久化请求后异步处理，读取时重新核验当前项目权限。 */
public interface AgentOperations {
    AgentRequestView create(UUID projectId, String idempotencyKey, CreateAgentRequest request);
    AgentRequestView get(UUID projectId, UUID requestId);
}
