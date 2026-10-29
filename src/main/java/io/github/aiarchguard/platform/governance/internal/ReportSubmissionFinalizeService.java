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

/**
 * 已持久化门禁之后的完成事务：关联提交、记录审计，并按当前 PR head 条件尝试挂接门禁。
 * 与接收导入和门禁写入分开；不会向 GitHub 发布外部状态，也不会改变门禁内容。
 */
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

    /** 条件完成 RECEIVED；只有本次完成胜者追加审计并尝试更新 PR 当前门禁。 */
    @Transactional
    public void complete(ReportSubmissionStore.Stored submission, GateEvaluationView gate, java.util.UUID actor) {
        ReportSubmissionView view = submission.view();
        // 重复恢复已完成提交时不重复写完成审计；数据库条件更新裁定并发胜者。
        if (!submissions.complete(view.projectId(), view.repositoryId(), view.id(), gate.id(), Instant.now(clock))) {
            return;
        }
        audit.record(new AuditEvent(actor, view.projectId(), "governance.report_submission.complete",
            AuditResult.SUCCESS, traceIds.currentTraceId(), Map.of("submissionId", view.id(),
                "gateEvaluationId", gate.id(), "outcome", gate.outcome().name())));
        // 迟到报告仍保留完成历史，但只有 commit 等于已验签当前 head 才能成为当前门禁。
        if (view.pullRequest() != null && webhooks.attachGateIfCurrent(view.projectId(), view.repositoryId(),
                view.pullRequest().externalId(), view.revision().commitSha(), gate.id())) {
            audit.record(new AuditEvent(actor, view.projectId(), "governance.github.current_gate",
                AuditResult.SUCCESS, traceIds.currentTraceId(), Map.of("submissionId", view.id(),
                    "gateEvaluationId", gate.id(), "pullRequestId", view.pullRequest().externalId())));
        }
    }
}
