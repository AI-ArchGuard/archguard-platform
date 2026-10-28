package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/**
 * 项目开关与修订号的只读投影。modelCallsAvailable 只反映本地条件，不证明 Key 有效或供应商可用。
 */
public record AgentSettingsView(boolean enabled, long revision, UUID enablementId,
        boolean modelCallsAvailable, String unavailableReason) { }
