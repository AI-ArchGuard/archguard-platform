package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** Local configuration status only; never a model probe or permission to spend. */
public interface AgentRuntimeAvailability {
    boolean available(UUID projectId);
}
