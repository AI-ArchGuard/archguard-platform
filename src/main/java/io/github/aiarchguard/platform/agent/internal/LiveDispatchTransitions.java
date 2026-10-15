package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
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
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered claim boundary. Claim and audit commit before any execution; no budget locks. */
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

    public LiveDispatchTransitions(AgentStore requests, LiveRequestStore links, ProjectAuthorization projects,
            CurrentActorProvider actors, AuthenticatedTaskScope identities, TraceIdProvider traces,
            AuditRecorder audit, Clock clock, PlatformTransactionManager manager) {
        this.requests = requests; this.links = links; this.projects = projects; this.actors = actors;
        this.identities = identities; this.traces = traces; this.audit = audit; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        results = new LiveResultTransitions(requests, audit, clock, manager);
    }

    public Claim start(UUID project, UUID request) {
        return transaction.execute(status -> {
            identities.requireActive(); projects.requireViewer(project);
            var snapshot = requests.find(project, request).orElseThrow(AgentNotFoundException::new);
            var view = snapshot.view();
            if (!actors.currentActor().id().equals(view.requesterId())) throw new ProjectPermissionDeniedException();
            if (!"QUEUED".equals(view.state())) return null;
            var link = links.find(project, request).orElseThrow(AgentEnablementInvalidStateException::new);
            requireContext(snapshot, link);
            if (!links.claim(link, view.requesterId(), Instant.now(clock))) return null;
            // CAS may wait on a row lock. Recheck before auditing/committing its tentative claim.
            var claimed = requests.find(project, request).orElseThrow(AgentNotFoundException::new);
            if (!"RUNNING".equals(claimed.view().state())) throw new AgentEnablementInvalidStateException();
            requireContext(claimed, link); identities.requireActive(); projects.requireViewer(project);
            audit.record(new AuditEvent(view.requesterId(), project, "agent.live.request.running", AuditResult.SUCCESS,
                view.traceId(), Map.of("requestId", request, "batchId", link.batchId(), "templateId", link.templateId(),
                    "enablementId", link.enablementId(), "credentialVersion", link.credentialVersion(),
                    "settingsRevision", link.settingsRevision(), "inputDigest", view.bindings().inputDigest())));
            requireContext(claimed, link); identities.requireActive();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    requireContext(claimed, link); identities.requireActive();
                }
            });
            return new Claim(this, claimed, link);
        });
    }

    /** Only the committed claim winner calls this; no retry, synthetic settlement or refund. */
    public void fail(Claim claim, String code) {
        if (claim == null || claim.issuer != this) throw new AgentUnavailableException();
        String safe = java.util.Set.of("MODEL_TIMEOUT", "MODEL_UNAVAILABLE", "AUTHORIZATION_REVOKED",
            "QUOTA_EXHAUSTED", "OUTPUT_INVALID", "INTERNAL_ERROR").contains(code == null ? "" : code)
                ? code : "INTERNAL_ERROR";
        results.finish(claim.snapshot(), null,
            new AgentRequestView.AgentFailure(safe, AgentApplicationService.message(safe)), null);
    }

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
