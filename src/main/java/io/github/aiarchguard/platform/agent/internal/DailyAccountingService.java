package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import java.time.Clock;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 日常记账测试使用的服务边界。此阶段尚未装配，所有操作均拒绝执行。 */
public final class DailyAccountingService {
    public DailyAccountingService(DailyAgentControlService controls, DailyAgentStore store, AgentStore requests,
            LiveDatabaseWork database, AuditRecorder audit, TraceIdProvider traces, Clock clock,
            long projectLimit, long deploymentLimit) {}
    public LiveAccountingOperations.Admission reserve(UUID project, UUID request, LongSupplier remaining) {
        throw new AgentUnavailableException();
    }
    public LiveAccountingOperations.SendApproval checkBeforeSend(UUID project, UUID request, LongSupplier remaining) {
        throw new AgentUnavailableException();
    }
    public LiveAccountingOperations.Outcome settle(UUID project, UUID request, String price, LiveTokenUsage usage) {
        throw new AgentUnavailableException();
    }
    public LiveAccountingOperations.Outcome unknown(UUID project, UUID request) { throw new AgentUnavailableException(); }
    public void stop(UUID project, UUID request, String code) { throw new AgentUnavailableException(); }
    public void completeSuccess(UUID project, UUID request) { throw new AgentUnavailableException(); }
}
