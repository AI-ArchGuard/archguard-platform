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

/**
 * 报告接收与导入的事务边界，由提交应用服务完成外层授权和元数据校验后调用。
 * 通过 ScanJob 的公开导入接口保存成功任务与 Finding，不排入本地 Runner；接收记录与导入共享当前事务。
 */
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

    /**
     * 首次写入 RECEIVED、导入报告并审计；导入校验失败会回滚本段写入，尚未进行门禁求值。
     * 并发重复请求在数据库唯一约束后复用胜者，不创建第二个任务。
     */
    @Transactional
    public ReportSubmissionOperations.Acceptance accept(UUID projectId, UUID repositoryId,
            String key, String digest, ReportSubmissionMetadata metadata, byte[] report) {
        UUID submissionId = UUID.randomUUID(), jobId = UUID.randomUUID(), actor = actors.currentActor().id();
        var view = new ReportSubmissionView(submissionId, projectId, repositoryId,
            metadata.ruleSetVersionId(), metadata.revision(), metadata.pullRequest(),
            metadata.reportSha256(), digest, ReportSubmissionStatus.RECEIVED, null, Instant.now(clock));
        if (!store.insert(view, key, jobId, metadata.scannerVersion(), metadata.schemaVersion(), actor)) {
            // 可能冲突于键，也可能冲突于摘要；复用前仍拒绝同键绑定不同请求。
            var byKey = store.byKey(projectId, key);
            if (byKey.isPresent() && !byKey.get().view().requestDigest().equals(digest)) {
                throw new GovernanceConflictException("Idempotency-Key is bound to different report inputs");
            }
            var existing = byKey.or(() -> store.byDigest(projectId, digest)).orElseThrow();
            return new ReportSubmissionOperations.Acceptance(existing.view(), true);
        }
        // 导入用例仍执行自身的授权、Schema 与归属校验，并参加当前同库事务。
        jobs.importCompleted(projectId, repositoryId, metadata.ruleSetVersionId(),
            jobId, metadata.scannerVersion(), report);
        audit.record(new AuditEvent(actor, projectId, "governance.report_submission.accept", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("submissionId", submissionId, "scanJobId", jobId,
                "reportSha256", metadata.reportSha256())));
        return new ReportSubmissionOperations.Acceptance(view, false);
    }
}
