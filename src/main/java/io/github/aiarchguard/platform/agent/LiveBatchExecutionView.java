package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Persisted execution facts only; this snapshot never grants permission to send or retry. */
public record LiveBatchExecutionView(String schemaVersion, Instant observedAt, LiveBatchView batch,
        String costPolicyVersion, BatchAccounting accounting, BatchStop stop, List<TemplateExecution> templates) {
    public LiveBatchExecutionView { templates = List.copyOf(templates); }
    public record BatchAccounting(int attemptCount, long reservedMicrousd, long spentMicrousd) { }
    public record BatchStop(UUID requestId, String code, Instant stoppedAt) { }
    public record TemplateExecution(UUID templateRequestId, String purpose, RequestExecution request,
            AttemptExecution attempt) { }
    public record RequestExecution(UUID id, String state, String failureCode, String traceId,
            Instant createdAt, Instant updatedAt) { }
    public record AttemptExecution(UUID attemptId, LocalDate utcDay, String priceCatalogVersion,
            long reservedMicrousd, Instant createdAt, Outcome outcome) { }
    public record Outcome(String state, Long actualMicrousd, LiveTokenUsage usage, Instant recordedAt) { }
}
