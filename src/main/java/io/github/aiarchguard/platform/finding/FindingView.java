package io.github.aiarchguard.platform.finding;

import java.util.List;
import java.util.UUID;

/**
 * 规范化 Finding 的公开视图，将 Scanner 确定性事实与本次扫描的人工处置、并发版本放在一起读取。
 * id 和 evidenceIds 使用 Platform UUID；scannerFindingId、subjectId 和 fingerprint 保留报告值。
 * fingerprint 不负责这里的去重，跨扫描治理由其他模块通过公开边界处理。
 */
public record FindingView(UUID id, UUID projectId, UUID jobId, String scannerFindingId, String fingerprint,
                          String ruleId, String ruleVersion, String severity, String subjectId, String message,
                          SourceLocation location, FindingDisposition disposition, long version,
                          List<UUID> evidenceIds) {
    /** 报告给出的相对源码位置；Finding 可无位置，Evidence 的位置由固定 Schema 要求。 */
    public record SourceLocation(String path, int startLine, int startColumn, int endLine, int endColumn) { }
}
