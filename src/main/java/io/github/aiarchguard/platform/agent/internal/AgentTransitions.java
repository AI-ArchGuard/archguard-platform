package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTransitions {
    private final AgentStore store;
    private final AgentBudgetStore budgets;
    private final AuditRecorder audit;
    private final Clock clock;

    AgentTransitions(AgentStore store, AgentBudgetStore budgets, AuditRecorder audit, Clock clock) {
        this.store = store; this.budgets = budgets; this.audit = audit; this.clock = clock;
    }

    @Transactional
    public Start start(AgentSnapshot snapshot, long estimate) {
        AgentRequestView view = snapshot.view();
        if (!store.claim(view.id(), Instant.now(clock))) return null;
        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!budgets.reserve(view.projectId(), day, estimate)) {
            var failure = new AgentRequestView.AgentFailure("QUOTA_EXHAUSTED",
                AgentApplicationService.message("QUOTA_EXHAUSTED"));
            store.finish(view.id(), null, failure, null, Instant.now(clock));
            record(view, "agent.request.failed", "QUOTA_EXHAUSTED", AuditResult.DENIED, Map.of());
            return null;
        }
        store.setReservation(view.id(), day, estimate);
        record(view, "agent.request.running", "NONE", AuditResult.SUCCESS,
            Map.of("estimatedCostMicrousd", estimate));
        return new Start(day, estimate);
    }

    @Transactional
    public void finish(AgentSnapshot snapshot, Start start, AgentRequestView.AgentResult result,
            AgentRequestView.AgentFailure failure, AgentRequestView.AgentUsage usage, boolean costUnknown) {
        AgentRequestView view = snapshot.view();
        if (!store.finish(view.id(), result, failure, usage, Instant.now(clock))) return;
        if (!costUnknown) {
            long actual = usage == null || usage.actualCostMicrousd() == null ? 0 : usage.actualCostMicrousd();
            budgets.settle(view.projectId(), start.day(), start.estimate(), actual);
        }
        java.util.HashMap<String, Object> metadata = new java.util.HashMap<>(Map.of("requestId", view.id(), "state",
            result == null ? "FAILED" : "SUCCEEDED", "failureCode", failure == null ? "NONE" : failure.code(),
            "inputDigest", view.bindings().inputDigest(), "estimatedCostMicrousd", start.estimate(),
            "inputTokens", usage == null ? 0 : usage.inputTokens(),
            "outputTokens", usage == null ? 0 : usage.outputTokens(),
            "latencyMs", usage == null ? 0 : usage.latencyMs(),
            "actualCostMicrousd", usage == null || usage.actualCostMicrousd() == null ? -1 : usage.actualCostMicrousd()));
        if (usage != null && usage.providerLatencyMs() != null) metadata.put("providerLatencyMs", usage.providerLatencyMs());
        if (usage != null && usage.providerResponseId() != null) metadata.put("providerResponseId", usage.providerResponseId());
        if (usage != null && usage.actualModelId() != null) metadata.put("actualModelId", usage.actualModelId());
        record(view, result == null ? "agent.request.failed" : "agent.request.succeeded",
            failure == null ? "NONE" : failure.code(), result == null ? AuditResult.FAILURE : AuditResult.SUCCESS,
            metadata);
    }

    @Transactional
    public void failQueued(AgentSnapshot snapshot, String code) {
        AgentRequestView view = snapshot.view();
        if (store.failQueued(view.id(), new AgentRequestView.AgentFailure(code,
                AgentApplicationService.message(code)), Instant.now(clock))) {
            record(view, "agent.request.failed", code, AuditResult.FAILURE, Map.of());
        }
    }

    @Transactional
    public void failUnknown(AgentSnapshot snapshot) {
        AgentRequestView view = snapshot.view();
        var failure = new AgentRequestView.AgentFailure("INTERNAL_ERROR",
            AgentApplicationService.message("INTERNAL_ERROR"));
        boolean changed = store.failQueued(view.id(), failure, Instant.now(clock))
            || store.failRunning(view.id(), failure, Instant.now(clock));
        if (changed) record(view, "agent.request.failed", "INTERNAL_ERROR", AuditResult.FAILURE,
            Map.of("costUnknown", true));
    }

    @Transactional
    public void recoverExpired(AgentSnapshot snapshot, Instant cutoff) {
        var view = snapshot.view();
        boolean unknownCost = "RUNNING".equals(view.state());
        String code = unknownCost ? "INTERNAL_ERROR" : "MODEL_TIMEOUT";
        if (store.expire(view.id(), cutoff, new AgentRequestView.AgentFailure(code,
                AgentApplicationService.message(code)), Instant.now(clock))) {
            record(view, "agent.request.failed", code, AuditResult.FAILURE,
                Map.of("costUnknown", unknownCost, "recovery", true));
        }
    }

    private void record(AgentRequestView view, String action, String code, AuditResult result,
            Map<String, Object> extra) {
        java.util.HashMap<String, Object> metadata = new java.util.HashMap<>(extra);
        metadata.put("requestId", view.id()); metadata.put("failureCode", code);
        metadata.put("inputDigest", view.bindings().inputDigest());
        metadata.put("scanJobId", view.bindings().scanJobId());
        metadata.put("reportSha256", view.bindings().reportSha256());
        metadata.put("findingIds", view.bindings().findingIds());
        metadata.put("documentVersionIds", view.bindings().documentVersions().stream()
            .map(AgentRequestView.DocumentVersionBinding::documentVersionId).toList());
        metadata.put("promptVersion", view.bindings().promptVersion());
        metadata.put("outputSchemaVersion", view.bindings().outputSchemaVersion());
        metadata.put("modelProtocolVersion", view.bindings().modelProtocolVersion());
        metadata.put("modelId", view.bindings().modelId());
        metadata.put("priceCatalogVersion", view.bindings().priceCatalogVersion());
        audit.record(new AuditEvent(view.requesterId(), view.projectId(), action, result, view.traceId(), metadata));
    }

    public record Start(LocalDate day, long estimate) {}
}
