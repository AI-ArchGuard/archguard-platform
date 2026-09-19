package io.github.aiarchguard.platform.finding.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.finding.DispositionView;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingDisposition;
import io.github.aiarchguard.platform.finding.FindingNotFoundException;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.finding.FindingVersionConflictException;
import io.github.aiarchguard.platform.finding.FindingView;
import io.github.aiarchguard.platform.finding.InvalidDispositionException;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
final class FindingApplicationService implements FindingOperations {
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final FindingStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;
    FindingApplicationService(ProjectAuthorization projects, CurrentActorProvider actors, FindingStore store,
                              AuditRecorder audit, TraceIdProvider traceIds, Clock clock) {
        this.projects=projects; this.actors=actors; this.store=store; this.audit=audit; this.traceIds=traceIds; this.clock=clock;
    }
    @Override public List<FindingView> list(UUID projectId, UUID jobId, int page, int size) {
        projects.requireViewer(projectId);
        if (page < 0 || size < 1 || size > 100) throw new InvalidDispositionException("Invalid page or size");
        return store.list(projectId, jobId, page, size);
    }
    @Override public EvidenceView evidence(UUID projectId, UUID jobId, UUID evidenceId) {
        projects.requireViewer(projectId);
        return store.findEvidence(projectId, jobId, evidenceId).orElseThrow(FindingNotFoundException::new);
    }
    @Override public FindingView disposition(UUID projectId, UUID jobId, UUID findingId, FindingDisposition next,
                                             String reason, long expectedVersion) {
        projects.requireMaintainer(projectId);
        if (next == null || reason == null || reason.isBlank() || reason.length() > 1000 || expectedVersion < 0) {
            throw new InvalidDispositionException("Disposition, reason, and a non-negative expected version are required");
        }
        FindingView current = store.find(projectId, jobId, findingId).orElseThrow(FindingNotFoundException::new);
        UUID actor = actors.currentActor().id();
        if (!store.updateDisposition(findingId, current.disposition(), next, expectedVersion, reason.trim(), actor,
                Instant.now(clock), UUID.randomUUID())) {
            throw new FindingVersionConflictException();
        }
        audit.record(new AuditEvent(actor, projectId, "finding.disposition", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("findingId", findingId, "from", current.disposition(), "to", next)));
        return store.find(projectId, jobId, findingId).orElseThrow(FindingNotFoundException::new);
    }
    @Override public List<DispositionView> dispositionHistory(UUID projectId, UUID jobId, UUID findingId) {
        projects.requireViewer(projectId);
        store.find(projectId, jobId, findingId).orElseThrow(FindingNotFoundException::new);
        return store.history(findingId);
    }
}
