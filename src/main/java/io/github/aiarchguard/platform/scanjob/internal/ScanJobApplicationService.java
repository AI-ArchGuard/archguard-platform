package io.github.aiarchguard.platform.scanjob.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import io.github.aiarchguard.platform.scanjob.InvalidScanJobException;
import io.github.aiarchguard.platform.scanjob.ScanJobConflictException;
import io.github.aiarchguard.platform.scanjob.ScanJobNotFoundException;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobResult;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import io.github.aiarchguard.platform.scanjob.ScanJobView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
final class ScanJobApplicationService implements ScanJobOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final CurrentActorProvider actors;
    private final ScanJobStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;
    private final RunnerCancellationPort cancellations;

    ScanJobApplicationService(ProjectAuthorization projects, RepositoryOperations repositories, RuleSetCatalog ruleSets,
                              CurrentActorProvider actors, ScanJobStore store, AuditRecorder audit,
                              TraceIdProvider traceIds, Clock clock, RunnerCancellationPort cancellations) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets; this.actors = actors;
        this.store = store; this.audit = audit; this.traceIds = traceIds; this.clock = clock; this.cancellations=cancellations;
    }

    @Override
    public ScanJobView submit(UUID projectId, UUID repositoryId, UUID versionId, String idempotencyKey) {
        projects.requireMaintainer(projectId);
        repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, versionId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new InvalidScanJobException("Idempotency-Key must contain between 1 and 128 characters");
        }
        String requestHash = sha256((repositoryId + "\n" + versionId).getBytes(StandardCharsets.UTF_8));
        var existing = store.findByIdempotency(projectId, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().requestSha256().equals(requestHash)) {
                throw new ScanJobConflictException("Idempotency-Key was already used for a different request");
            }
            return view(existing.get());
        }
        UUID actorId = actors.currentActor().id();
        ScanJobRecord job = new ScanJobRecord(UUID.randomUUID(), projectId, repositoryId, versionId,
            idempotencyKey, requestHash, ScanJobStatus.QUEUED, null, actorId, Instant.now(clock), null, null,
            null, 0, null, 0, null, null, null, null, false, null, null);
        try {
            store.insert(job);
        } catch (DuplicateKeyException exception) {
            ScanJobRecord raced = store.findByIdempotency(projectId, idempotencyKey).orElseThrow();
            if (!raced.requestSha256().equals(requestHash)) {
                throw new ScanJobConflictException("Idempotency-Key was already used for a different request");
            }
            return view(raced);
        }
        record(actorId, projectId, "scanjob.submit", Map.of("jobId", job.id()));
        return view(job);
    }

    @Override public ScanJobView get(UUID projectId, UUID jobId) {
        projects.requireViewer(projectId);
        return view(store.find(projectId, jobId).orElseThrow(ScanJobNotFoundException::new));
    }
    @Override public List<ScanJobView> list(UUID projectId, int page, int size) {
        projects.requireViewer(projectId);
        if (page < 0 || size < 1 || size > 100) throw new InvalidScanJobException("Invalid page or size");
        return store.list(projectId, page, size).stream().map(ScanJobApplicationService::view).toList();
    }
    @Override public ScanJobView cancel(UUID projectId, UUID jobId) {
        projects.requireMaintainer(projectId);
        ScanJobRecord job = store.cancel(projectId, jobId, Instant.now(clock)).orElseThrow(ScanJobNotFoundException::new);
        if(job.status()==ScanJobStatus.CANCEL_REQUESTED)cancellations.request(job.id(),job.attemptToken());
        record(actors.currentActor().id(), projectId, "scanjob.cancel", Map.of("jobId", jobId, "status", job.status()));
        return view(job);
    }
    @Override public ScanJobResult result(UUID projectId, UUID jobId) {
        projects.requireViewer(projectId);
        ScanJobRecord job = store.find(projectId, jobId).orElseThrow(ScanJobNotFoundException::new);
        if (job.status() != ScanJobStatus.SUCCEEDED || job.report() == null) {
            throw new ScanJobConflictException("A successful result is not available for this job");
        }
        return new ScanJobResult(job.report(), job.reportSha256(), job.scannerVersion(), job.schemaVersion());
    }

    private void record(UUID actor, UUID project, String action, Map<String, Object> metadata) {
        audit.record(new AuditEvent(actor, project, action, AuditResult.SUCCESS, traceIds.currentTraceId(), metadata));
    }
    private static ScanJobView view(ScanJobRecord j) {
        return new ScanJobView(j.id(), j.projectId(), j.repositoryId(), j.ruleSetVersionId(), j.status(), j.outcome(),
            j.createdBy(), j.createdAt(), j.startedAt(), j.completedAt(), j.attempt(), j.version(),
            j.failureCode(), j.failureMessage(), j.reportSha256());
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
