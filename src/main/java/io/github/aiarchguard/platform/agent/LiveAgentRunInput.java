package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** 真实入口仅接受批次与模板的不可变选择 ID；模型正文、凭据、价格和目标地址均由服务端控制。 */
public record LiveAgentRunInput(UUID batchId, UUID templateRequestId) { }
