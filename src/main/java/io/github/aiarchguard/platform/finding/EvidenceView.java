package io.github.aiarchguard.platform.finding;

import java.util.UUID;

/**
 * 归属于项目和扫描任务的规范化证据视图，由持久化适配器提供给已授权的读取用例。
 * id 是 Platform 的任务内稳定 UUID，scannerEvidenceId 保留 Scanner 报告中的原始证据标识。
 */
public record EvidenceView(UUID id, UUID projectId, UUID jobId, String scannerEvidenceId, String kind,
                           String summary, FindingView.SourceLocation location) {
}
