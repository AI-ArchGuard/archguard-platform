package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GateWriteService {
    private final GateEvaluationStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;

    GateWriteService(GateEvaluationStore store, AuditRecorder audit, TraceIdProvider traceIds) {
        this.store = store; this.audit = audit; this.traceIds = traceIds;
    }

    @Transactional
    public GateEvaluationView persist(GateEvaluationView value, String key, String requestSha256, UUID actorId) {
        if (!store.insert(value, key, requestSha256, actorId)) {
            GateEvaluationStore.Stored existing = store.byKey(value.projectId(), key).orElseThrow();
            if (!existing.requestSha256().equals(requestSha256)) {
                throw new GovernanceConflictException("Idempotency-Key is bound to different gate inputs");
            }
            return existing.view();
        }
        store.seal(value.id());
        audit.record(new AuditEvent(actorId, value.projectId(), "governance.gate.evaluate", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("gateEvaluationId", value.id(), "outcome", value.outcome().name(),
                "ciExitCode", value.ciExitCode(), "blockedCount", value.blockedCount())));
        return value;
    }
}
