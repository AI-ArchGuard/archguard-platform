package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentEnablementOperations;
import io.github.aiarchguard.platform.agent.AgentLiveRunOperations;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.LiveAgentRunInput;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.identity.InvalidCurrentActorException;
import io.github.aiarchguard.platform.identity.InactiveAuthenticatedTaskException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 独立 live HTTP 入口的应用服务：先核对部署及精确批次，再有界创建持久化请求，QUEUED 才显式调度；
 * 拒绝审计独立清理，不隐式切换原合成请求。
 */
@Service
final class PersonalLiveRunService implements AgentLiveRunOperations {
    private final AgentEnablementOperations controls;
    private final PersonalLivePolicy policy;
    private final LiveDatabaseWork database;
    private final LiveRequestOperations creation;
    private final PersonalLiveRuntime runtime;
    private final AuditRecorder audit;
    private final CurrentActorProvider actors;
    private final TraceIdProvider traces;
    PersonalLiveRunService(AgentEnablementOperations controls, PersonalLivePolicy policy,
            LiveDatabaseWork database, LiveRequestOperations creation, PersonalLiveRuntime runtime,
            AuditRecorder audit, CurrentActorProvider actors, TraceIdProvider traces) {
        this.controls = controls; this.policy = policy; this.database = database; this.creation = creation;
        this.runtime = runtime; this.audit = audit; this.actors = actors; this.traces = traces;
    }
    @Override public void authorize(UUID project, String origin, boolean secure) {
        controls.authorizeWrite(project, origin, secure);
        if (!policy.available(project)) throw new AgentUnavailableException();
        runtime.requireAvailable();
    }
    /**
     * HTTP 申请阶段共享有限预算完成策略核验与创建；持久化成功后才显式调度，worker 的执行预算从后台开始时另行建立。
     */
    @Override public AgentRequestView run(UUID project, LiveAgentRunInput input, String key, String origin, boolean secure) {
        var budget = new LiveExecutionBudget(System::nanoTime);
        try {
            authorize(project, origin, secure);
            if (project == null || input == null || input.batchId() == null || input.templateRequestId() == null
                    || key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(c -> c < 33 || c > 126)) {
                throw new AgentInvalidException();
            }
            database.transaction(budget, true, () -> {
                policy.requireBatch(project, input.batchId(), input.templateRequestId()); return null;
            });
        } catch (RuntimeException rejected) {
            rejected(project, "agent.live.run_rejected", null, input, rejected);
            throw rejected;
        }
        // Creation owns its own atomic success/rejection audit and may not use the unbounded overload.
        var view = creation.create(project, input.batchId(), input.templateRequestId(), key, origin, secure, budget);
        if ("QUEUED".equals(view.state())) {
            try { runtime.dispatch(project, view.id()); }
            catch (RuntimeException rejected) {
                rejected(project, "agent.live.dispatch_rejected", view, input, rejected);
                throw rejected;
            }
        }
        return view;
    }

    private void rejected(UUID project, String action, AgentRequestView view, LiveAgentRunInput input, RuntimeException failure) {
        String reason = failure instanceof AgentInvalidException ? "INVALID_INPUT"
            : failure instanceof ProjectPermissionDeniedException || failure instanceof InvalidCurrentActorException
                || failure instanceof InactiveAuthenticatedTaskException ? "AUTHORIZATION_REVOKED" : "MODEL_UNAVAILABLE";
        var metadata = new java.util.HashMap<String, Object>(); metadata.put("reason", reason);
        if (view != null) metadata.put("requestId", view.id());
        if (input != null && input.batchId() != null) metadata.put("batchId", input.batchId());
        if (input != null && input.templateRequestId() != null) metadata.put("templateId", input.templateRequestId());
        try {
            UUID actor = view == null ? actors.currentActor().id() : view.requesterId();
            String trace = view == null ? traces.currentTraceId() : view.traceId();
            database.cleanup(() -> { audit.record(new AuditEvent(actor, project, action,
                "AUTHORIZATION_REVOKED".equals(reason) ? AuditResult.DENIED : AuditResult.FAILURE, trace, metadata)); return null; });
        } catch (RuntimeException auditUnavailable) { throw new AgentUnavailableException(); }
    }
}
