package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 批次、模板请求、尝试和结算的持久化事实快照。无尝试与 UNKNOWN 有不同含义；该视图不能授权发送或重试。 */
public record LiveBatchExecutionView(String schemaVersion, Instant observedAt, LiveBatchView batch,
        String costPolicyVersion, BatchAccounting accounting, BatchStop stop, List<TemplateExecution> templates) {
    public LiveBatchExecutionView { templates = List.copyOf(templates); }
    public record BatchAccounting(int attemptCount, long reservedMicrousd, long spentMicrousd) { }
    public record BatchStop(UUID requestId, String code, Instant stoppedAt) { }
    /** 一个模板唯一槽位的观察结果；request 为空代表尚未分配，attempt 为空代表尚未预留，不表示可重试。 */
    public record TemplateExecution(UUID templateRequestId, String purpose, RequestExecution request,
            AttemptExecution attempt) { }
    public record RequestExecution(UUID id, String state, String failureCode, String traceId,
            Instant createdAt, Instant updatedAt) { }
    public record AttemptExecution(UUID attemptId, LocalDate utcDay, String priceCatalogVersion,
            long reservedMicrousd, Instant createdAt, Outcome outcome) { }
    /** SETTLED 包含校验后的用量和实际费用；UNKNOWN 保留预留且缺少可信用量。 */
    public record Outcome(String state, Long actualMicrousd, LiveTokenUsage usage, Instant recordedAt) { }
}
