package io.github.aiarchguard.platform.agent;

import java.util.UUID;

public record AgentSettingsUpdate(Boolean enabled, UUID enablementId) { }
