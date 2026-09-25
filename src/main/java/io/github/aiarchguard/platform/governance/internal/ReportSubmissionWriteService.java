package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.GovernanceConflictException;
import io.github.aiarchguard.platform.governance.ReportSubmissionMetadata;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionStatus;
import io.github.aiarchguard.platform.governance.ReportSubmissionView;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.scanjob.ScanJobImportOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReportSubmissionWriteService {
    private final ReportSubmissionStore store;
    private final ScanJobImportOperations jobs;
    private final CurrentActorProvider actors;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;

    ReportSubmissionWriteService(ReportSubmissionStore store, ScanJobImportOperations jobs,
            CurrentActorProvider actors, AuditRecorder audit, TraceIdProvider traceIds, Clock clock) {
        this.store = store; this.jobs = jobs; this.actors = actors; this.audit = audit;
        this.traceIds = traceIds; this.clock = clock;
    }

    @Transactional
    public ReportSubmissionOperations.Acceptance accept(UUID projectId, UUID repositoryId,
            String key, String digest, ReportSubmissionMetadata metadata, byte[] report) {
        UUID submissionId = UUID.randomUUID(), jobId = UUID.randomUUID(), actor = actors.currentActor().id();
        var view = new ReportSubmissionView(submissionId, projectId, repositoryId,
            metadata.ruleSetVersionId(), metadata.revision(), metadata.pullRequest(),
            metadata.reportSha256(), digest, ReportSubmissionStatus.RECEIVED, null, Instant.now(clock));
        if (!store.insert(view, key, jobId, metadata.scannerVersion(), metadata.schemaVersion(), actor)) {
            var byKey = store.byKey(projectId, key);
            if (byKey.isPresent() && !byKey.get().view().requestDigest().equals(digest)) {
                throw new GovernanceConflictException("Idempotency-Key is bound to different report inputs");
            }
            var existing = byKey.or(() -> store.byDigest(projectId, digest)).orElseThrow();
            return new ReportSubmissionOperations.Acceptance(existing.view(), true);
        }
        jobs.importCompleted(projectId, repositoryId, metadata.ruleSetVersionId(),
            jobId, metadata.scannerVersion(), report);
        audit.record(new AuditEvent(actor, projectId, "governance.report_submission.accept", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("submissionId", submissionId, "scanJobId", jobId,
                "reportSha256", metadata.reportSha256())));
        return new ReportSubmissionOperations.Acceptance(view, false);
    }
}
