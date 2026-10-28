package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** 个人真实请求的显式应用入口。调用方只选已批准批次与模板，由服务端创建并调度独立请求，与原合成入口分离。 */
public interface AgentLiveRunOperations {
    void authorize(UUID project, String origin, boolean secure);
    /** 按精确批次与模板创建独立 live 请求；幂等重放返回已有请求，不允许通过换键绕过模板唯一槽。 */
    AgentRequestView run(UUID project, LiveAgentRunInput input, String idempotencyKey, String origin, boolean secure);
}
