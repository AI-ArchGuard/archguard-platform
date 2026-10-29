package io.github.aiarchguard.platform.scanjob;

import java.time.Instant;
import java.util.UUID;

/**
 * 面向调用者的任务元数据快照，包含执行状态、规则结论、尝试次数、版本和失败摘要。
 * attempt 统计认领次数；version 随主要状态更新递增，不在本接口充当客户端条件更新参数。
 * 报告单独读取，内部幂等摘要、租约和 attempt token 不对外暴露。
 */
public record ScanJobView(UUID id, UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
                          ScanJobStatus status, ScanJobOutcome outcome, UUID createdBy, Instant createdAt,
                          Instant startedAt, Instant completedAt, int attempt, long version,
                          String failureCode, String failureMessage, String reportSha256) {
}
