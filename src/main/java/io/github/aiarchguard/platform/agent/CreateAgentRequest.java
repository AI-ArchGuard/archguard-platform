package io.github.aiarchguard.platform.agent;

import java.util.List;
import java.util.UUID;

/** 原合成请求的资源选择 DTO；只提交用途和事实版本 ID，由应用服务授权读取并构建最小模型输入。 */
public record CreateAgentRequest(String purpose, UUID scanJobId, String reportSha256,
        UUID prHeadRevisionId, List<UUID> findingIds, List<UUID> documentVersionIds) {}
