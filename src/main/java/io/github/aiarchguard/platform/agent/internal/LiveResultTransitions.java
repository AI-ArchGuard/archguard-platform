package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered real-result writer. Never touches the synthetic budget store. */
public final class LiveResultTransitions {
    private final AgentStore store;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final LiveDatabaseWork database;

    public LiveResultTransitions(AgentStore store, AuditRecorder audit, Clock clock, PlatformTransactionManager manager) {
        this(store, audit, clock, manager, new LiveDatabaseWork() { });
    }

    public LiveResultTransitions(AgentStore store, AuditRecorder audit, Clock clock, PlatformTransactionManager manager,
            LiveDatabaseWork database) {
        this.store = store; this.audit = audit; this.clock = clock;
        this.database = Objects.requireNonNull(database);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void finish(AgentSnapshot snapshot, AgentRequestView.AgentResult result,
            AgentRequestView.AgentFailure failure, AgentRequestView.AgentUsage usage) {
        // Success cannot bypass the execution deadline through the failure-only entry point.
        if (result != null || failure == null) throw new IllegalArgumentException("Invalid live result state");
        persist(snapshot, null, failure, usage, () -> {});
    }

    /** Success-only boundary; checks after row-lock waits and immediately before commit. */
    public void finishChecked(AgentSnapshot snapshot, AgentRequestView.AgentResult result,
            AgentRequestView.AgentUsage usage, Runnable deadline) {
        if (result == null) throw new IllegalArgumentException("Invalid live result state");
        persist(snapshot, result, null, usage, Objects.requireNonNull(deadline));
    }

    /** The live core never falls back to the compatibility-only unbounded writer. */
    public void finishBounded(AgentSnapshot snapshot, AgentRequestView.AgentResult result,
            AgentRequestView.AgentUsage usage, LongSupplier remainingNanos) {
        if (result == null) throw new IllegalArgumentException("Invalid live result state");
        Runnable deadline = () -> checkRemaining(remainingNanos);
        deadline.run();
        database.transaction(remainingNanos, false, () -> {
            persistBody(snapshot, result, null, usage, deadline);
            return null;
        });
    }

    /** Failure persistence gets an independent finite budget, not a renewed model/send deadline. */
    public void failBounded(AgentSnapshot snapshot, AgentRequestView.AgentFailure failure,
            AgentRequestView.AgentUsage usage) {
        if (failure == null) throw new IllegalArgumentException("Invalid live result state");
        database.cleanup(() -> {
            persistBody(snapshot, null, failure, usage, () -> {});
            return null;
        });
    }

    private void persist(AgentSnapshot snapshot, AgentRequestView.AgentResult result,
            AgentRequestView.AgentFailure failure, AgentRequestView.AgentUsage usage, Runnable deadline) {
        transaction.executeWithoutResult(status -> persistBody(snapshot, result, failure, usage, deadline));
    }

    private void persistBody(AgentSnapshot snapshot, AgentRequestView.AgentResult result,
            AgentRequestView.AgentFailure failure, AgentRequestView.AgentUsage usage, Runnable deadline) {
        deadline.run();
        var view = snapshot.view();
        if (!store.finish(view.id(), result, failure, usage, Instant.now(clock))) return;
        // The CAS may have waited on a row lock. Expiry now rolls back its tentative success.
        deadline.run();
        var metadata = new HashMap<String, Object>();
        metadata.put("requestId", view.id()); metadata.put("inputDigest", view.bindings().inputDigest());
        metadata.put("state", result == null ? "FAILED" : "SUCCEEDED");
        metadata.put("failureCode", failure == null ? "NONE" : failure.code());
        metadata.put("scanJobId", view.bindings().scanJobId()); metadata.put("reportSha256", view.bindings().reportSha256());
        metadata.put("findingIds", view.bindings().findingIds());
        metadata.put("documentVersionIds", view.bindings().documentVersions().stream()
            .map(AgentRequestView.DocumentVersionBinding::documentVersionId).toList());
        metadata.put("promptVersion", view.bindings().promptVersion());
        metadata.put("modelProfileVersion", view.bindings().modelProfileVersion());
        metadata.put("modelProtocolVersion", view.bindings().modelProtocolVersion());
        metadata.put("modelId", view.bindings().modelId());
        metadata.put("outputSchemaVersion", view.bindings().outputSchemaVersion());
        metadata.put("priceCatalogVersion", view.bindings().priceCatalogVersion());
        if (usage != null) {
            metadata.put("inputTokens", usage.inputTokens()); metadata.put("outputTokens", usage.outputTokens());
            metadata.put("latencyMs", usage.latencyMs()); metadata.put("providerLatencyMs", usage.providerLatencyMs());
            metadata.put("estimatedCostMicrousd", usage.estimatedCostMicrousd());
            metadata.put("actualCostMicrousd", usage.actualCostMicrousd() == null ? "UNKNOWN" : usage.actualCostMicrousd());
            metadata.put("providerResponseId", usage.providerResponseId()); metadata.put("actualModelId", usage.actualModelId());
        }
        audit.record(new AuditEvent(view.requesterId(), view.projectId(), result == null ? "agent.live.result_failed" : "agent.live.result_succeeded",
            result == null ? AuditResult.FAILURE : AuditResult.SUCCESS, view.traceId(), metadata));
        deadline.run();
        if (result != null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { deadline.run(); }
        });
    }

    private static void checkRemaining(LongSupplier remainingNanos) {
        if (remainingNanos == null) throw new AgentUnavailableException();
        if (Thread.currentThread().isInterrupted()) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
        long remaining;
        try { remaining = remainingNanos.getAsLong(); }
        catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
        if (Thread.currentThread().isInterrupted() || remaining <= 0) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
        if (remaining > 30_000_000_000L) throw new AgentUnavailableException();
    }
}
