package io.github.aiarchguard.platform.agent.internal;

import java.time.LocalDate;
import java.util.UUID;

public interface AgentBudgetStore {
    boolean reserve(UUID projectId, LocalDate day, long estimatedMicrousd);
    void settle(UUID projectId, LocalDate day, long reservedMicrousd, long actualMicrousd);
}
