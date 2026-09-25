package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.ReportSubmissionView;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReportSubmissionFinalizeService {
    private final ReportSubmissionStore submissions;
    private final GithubWebhookStore webhooks;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;

    ReportSubmissionFinalizeService(ReportSubmissionStore submissions, GithubWebhookStore webhooks,
            AuditRecorder audit, TraceIdProvider traceIds, Clock clock) {
        this.submissions = submissions; this.webhooks = webhooks; this.audit = audit;
        this.traceIds = traceIds; this.clock = clock;
    }

    @Transactional
    public void complete(ReportSubmissionStore.Stored submission, GateEvaluationView gate, java.util.UUID actor) {
        ReportSubmissionView view = submission.view();
        if (!submissions.complete(view.projectId(), view.repositoryId(), view.id(), gate.id(), Instant.now(clock))) {
            return;
        }
        audit.record(new AuditEvent(actor, view.projectId(), "governance.report_submission.complete",
            AuditResult.SUCCESS, traceIds.currentTraceId(), Map.of("submissionId", view.id(),
                "gateEvaluationId", gate.id(), "outcome", gate.outcome().name())));
        if (view.pullRequest() != null && webhooks.attachGateIfCurrent(view.projectId(), view.repositoryId(),
                view.pullRequest().externalId(), view.revision().commitSha(), gate.id())) {
            audit.record(new AuditEvent(actor, view.projectId(), "governance.github.current_gate",
                AuditResult.SUCCESS, traceIds.currentTraceId(), Map.of("submissionId", view.id(),
                    "gateEvaluationId", gate.id(), "pullRequestId", view.pullRequest().externalId())));
        }
    }
}
