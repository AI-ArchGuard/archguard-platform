package io.github.aiarchguard.platform.scanjob.internal;

import io.github.aiarchguard.platform.scanjob.ScanJobOutcome;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * ScanJob 模块内部的完整持久化快照，包含幂等请求、状态、租约、attempt token 和报告附件。
 * leaseUntil 控制能否重新认领，attemptToken 区分不同执行尝试；它们不属于公开任务视图。
 * reportPartial 表示故障附件，与可供正常结果查询使用的成功报告区分；数组始终防御性复制。
 */
public record ScanJobRecord(UUID id, UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
                            String idempotencyKey, String requestSha256, ScanJobStatus status,
                            ScanJobOutcome outcome, UUID createdBy, Instant createdAt, Instant startedAt,
                            Instant completedAt, Instant leaseUntil, int attempt, UUID attemptToken,
                            long version, String failureCode, String failureMessage, byte[] report,
                            String reportSha256, boolean reportPartial, String scannerVersion,
                            String schemaVersion) {
    public ScanJobRecord {
        report = report == null ? null : report.clone();
    }
    @Override public byte[] report() { return report == null ? null : report.clone(); }
}
