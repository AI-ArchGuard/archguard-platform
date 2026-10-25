package io.github.aiarchguard.platform.finding.internal;

import io.github.aiarchguard.platform.finding.FindingView;
import java.util.UUID;

/**
 * 报告消费器交给写入端口的内部证据数据，已通过 Schema 和引用语义校验。
 * id 由 jobId 与报告 scannerId 稳定生成，保留 scannerId 便于回到原报告追溯。
 */
public record NormalizedEvidence(UUID id, UUID projectId, UUID jobId, String scannerId, String kind,
                                 String summary, FindingView.SourceLocation location) { }
