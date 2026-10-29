package io.github.aiarchguard.platform.finding.internal;

import io.github.aiarchguard.platform.finding.FindingView;
import java.util.List;
import java.util.UUID;

/**
 * 已校验报告中的 Finding 写入数据：保留规则事实、指纹和报告内 subjectId，证据引用换为 Platform UUID。
 * 本类型没有人工处置字段；JDBC 首次落库时统一初始化为 OPEN、版本零。
 */
public record NormalizedFinding(UUID id, UUID projectId, UUID jobId, String scannerId, String fingerprint,
                                String ruleId, String ruleVersion, String severity, String subjectId,
                                String message, FindingView.SourceLocation location, List<UUID> evidenceIds) { }
