package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** 项目开关更新 DTO；启用时选择已有确认 ID，实际有效性和修订号比较由应用服务检查。 */
public record AgentSettingsUpdate(Boolean enabled, UUID enablementId) { }
