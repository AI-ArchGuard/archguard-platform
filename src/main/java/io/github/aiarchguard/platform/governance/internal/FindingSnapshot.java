package io.github.aiarchguard.platform.governance.internal;

/**
 * 指纹计算器交给基线存储和分类器的单项快照：逻辑指纹用于跨扫描关联，载荷摘要用于冲突检查。
 * 严重性和 scannerFindingId 保留当次事实供门禁与追溯使用，不是逻辑指纹的输入。
 */
public record FindingSnapshot(String fingerprint, String payloadSha256, String ruleId, String ruleVersion,
                              String severity, String scannerFindingId) { }
