package io.github.aiarchguard.platform.agent;

import java.util.UUID;

public interface AgentEnablementOperations {
    void authorizeWrite(UUID projectId, String origin, boolean secure);
    AgentSettingsView settings(UUID projectId);
    AgentSettingsView update(UUID projectId, long expectedRevision, AgentSettingsUpdate request);
    PersonalEnablementView approve(UUID projectId, PersonalEnablementInput request);
    PersonalEnablementView get(UUID projectId, UUID enablementId);
    PersonalEnablementView revoke(UUID projectId, UUID enablementId);
}
