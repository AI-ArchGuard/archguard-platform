package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** 仅查询当前项目的本地装配状态；实现不得据此探测模型或认定本次发送及费用已获准。 */
public interface AgentRuntimeAvailability {
    boolean available(UUID projectId);
}
