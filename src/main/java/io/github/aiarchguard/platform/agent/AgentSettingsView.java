package io.github.aiarchguard.platform.agent;

import java.util.UUID;

public record AgentSettingsView(boolean enabled, long revision, UUID enablementId,
        boolean modelCallsAvailable, String unavailableReason) { }
