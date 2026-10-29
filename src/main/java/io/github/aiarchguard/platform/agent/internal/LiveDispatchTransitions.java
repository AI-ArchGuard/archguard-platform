package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.AuthenticatedTaskScope;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 真实请求领取与审计的独立事务边界。精确关联 CAS 胜者取得进程内 Claim，提交后才执行；
 * 失败收尾须持有该所有权，不能替重复调度者终结共享请求。
 */
public final class LiveDispatchTransitions {
    private final AgentStore requests;
    private final LiveRequestStore links;
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final AuthenticatedTaskScope identities;
    private final TraceIdProvider traces;
    private final AuditRecorder audit;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final LiveResultTransitions results;
    private final LiveAccountingOperations accounting;
    private final LiveDatabaseWork database;
    private final boolean bounded;

    public LiveDispatchTransitions(AgentStore requests, LiveRequestStore links, ProjectAuthorization projects,
            CurrentActorProvider actors, AuthenticatedTaskScope identities, TraceIdProvider traces,
            AuditRecorder audit, Clock clock, PlatformTransactionManager manager) {
        this(requests, links, projects, actors, identities, traces, audit, clock, manager, null, null, false);
    }

    public LiveDispatchTransitions(AgentStore requests, LiveRequestStore links, ProjectAuthorization projects,
            CurrentActorProvider actors, AuthenticatedTaskScope identities, TraceIdProvider traces,
            AuditRecorder audit, Clock clock, PlatformTransactionManager manager,
            LiveAccountingOperations accounting, LiveDatabaseWork database) {
        this(requests, links, projects, actors, identities, traces, audit, clock, manager,
            Objects.requireNonNull(accounting), Objects.requireNonNull(database), true);
    }

    private LiveDispatchTransitions(AgentStore requests, LiveRequestStore links, ProjectAuthorization projects,
            CurrentActorProvider actors, AuthenticatedTaskScope identities, TraceIdProvider traces,
            AuditRecorder audit, Clock clock, PlatformTransactionManager manager,
            LiveAccountingOperations accounting, LiveDatabaseWork database, boolean bounded) {
        this.requests = requests; this.links = links; this.projects = projects; this.actors = actors;
        this.identities = identities; this.traces = traces; this.audit = audit; this.clock = clock;
        this.accounting = accounting; this.bounded = bounded;
        this.database = database;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        results = bounded ? new LiveResultTransitions(requests, audit, clock, manager, database)
            : new LiveResultTransitions(requests, audit, clock, manager);
    }

    public Claim start(UUID project, UUID request) {
        return transaction.execute(status -> startBody(project, request, () -> {}));
    }

    public Claim start(UUID project, UUID request, LongSupplier remainingNanos) {
        if (!bounded) throw new AgentUnavailableException();
        var budget = new LiveExecutionBudget(System::nanoTime, remainingNanos);
        Runnable deadline = () -> budget.requireRemaining();
        deadline.run();
        return database.transaction(budget, false, () -> startBody(project, request, deadline));
    }

    /**
     * 核对原请求者、trace、队列年龄与完整关联后 CAS 领取；锁等待、审计后和提交前重新检查期限及身份，防止迟到领取提交。
     */
    private Claim startBody(UUID project, UUID request, Runnable deadline) {
        deadline.run(); identities.requireActive(); projects.requireViewer(project);
        var snapshot = requests.find(project, request).orElseThrow(AgentNotFoundException::new);
        var view = snapshot.view();
        if (!actors.currentActor().id().equals(view.requesterId())) throw new ProjectPermissionDeniedException();
        // A context claim needs an independently persisted stop even if preflight creates no attempt.
        // The compatibility boundary must not acquire ownership it cannot safely finalize.
        if (!bounded && LiveCostPolicy.CONTEXT_VERSION.equals(view.bindings().priceCatalogVersion())) {
            throw new AgentUnavailableException();
        }
        if (!"QUEUED".equals(view.state())) return null;
        var link = links.find(project, request).orElseThrow(AgentEnablementInvalidStateException::new);
        requireContext(snapshot, link); deadline.run(); identities.requireActive();
        if (!links.claim(link, view.requesterId(), Instant.now(clock))) return null;
        // CAS may wait on a row lock. Recheck before auditing/committing its tentative claim.
        deadline.run(); identities.requireActive();
        var claimed = requests.find(project, request).orElseThrow(AgentNotFoundException::new);
        if (!"RUNNING".equals(claimed.view().state())) throw new AgentEnablementInvalidStateException();
        requireContext(claimed, link); identities.requireActive(); projects.requireViewer(project); deadline.run();
        audit.record(new AuditEvent(view.requesterId(), project, "agent.live.request.running", AuditResult.SUCCESS,
            view.traceId(), Map.of("requestId", request, "batchId", link.batchId(), "templateId", link.templateId(),
                "enablementId", link.enablementId(), "credentialVersion", link.credentialVersion(),
                "settingsRevision", link.settingsRevision(), "inputDigest", view.bindings().inputDigest())));
        deadline.run(); requireContext(claimed, link); identities.requireActive();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                deadline.run(); requireContext(claimed, link); identities.requireActive();
            }
        });
        return new Claim(this, claimed, link);
    }

    /** Only the committed claim winner calls this; no retry, synthetic settlement or refund. */
    public void fail(Claim claim, String code) {
        if (claim == null || claim.issuer != this) throw new AgentUnavailableException();
        String safe = java.util.Set.of("MODEL_TIMEOUT", "MODEL_UNAVAILABLE", "AUTHORIZATION_REVOKED",
            "QUOTA_EXHAUSTED", "OUTPUT_INVALID", "INTERNAL_ERROR").contains(code == null ? "" : code)
                ? code : "INTERNAL_ERROR";
        var snapshot = claim.snapshot();
        var failure = new AgentRequestView.AgentFailure(safe, AgentApplicationService.message(safe));
        if (LiveCostPolicy.CONTEXT_VERSION.equals(snapshot.view().bindings().priceCatalogVersion())) {
            if (!bounded) throw new AgentUnavailableException();
            try {
                accounting.stopBatch(snapshot.view().projectId(), claim.link().batchId(), snapshot.view().id(), safe);
            } catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
        }
        if (bounded) {
            try { results.failBounded(snapshot, failure, null); }
            catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
        } else {
            results.finish(snapshot, null, failure, null);
        }
    }

    /**
     * 要求持久化 trace 与当前隔离作用域一致，并限制创建后 60 秒的队列年龄；UUID 相等不能代替可信身份有效性。
     */
    private void requireContext(AgentSnapshot snapshot, LiveRequestStore.Link link) {
        var view = snapshot.view();
        if (!actors.currentActor().id().equals(view.requesterId())) throw new ProjectPermissionDeniedException();
        if (view.traceId() == null || !view.traceId().matches("[0-9a-f]{32}")
                || !view.traceId().equals(traces.currentTraceId()) || !view.id().equals(link.requestId())
                || !view.projectId().equals(link.projectId()) || !view.createdAt().equals(link.createdAt())) {
            throw new AgentEnablementInvalidStateException();
        }
        Instant now = Instant.now(clock);
        if (Thread.currentThread().isInterrupted() || view.createdAt().isAfter(now)
                || !view.createdAt().plusSeconds(60).isAfter(now)) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
    }

    /** Opaque, non-serializable in-process ownership; cannot be constructed by a dispatcher caller. */
    public static final class Claim {
        private final LiveDispatchTransitions issuer;
        private final AgentSnapshot snapshot;
        private final LiveRequestStore.Link link;
        private Claim(LiveDispatchTransitions issuer, AgentSnapshot snapshot, LiveRequestStore.Link link) {
            this.issuer = issuer; this.snapshot = snapshot; this.link = link;
        }
        public AgentSnapshot snapshot() { return snapshot; }
        public LiveRequestStore.Link link() { return link; }
        @Override public String toString() { return "Live dispatch claim [content omitted]"; }
    }
}
