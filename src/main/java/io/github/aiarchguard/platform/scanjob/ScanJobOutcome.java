package io.github.aiarchguard.platform.scanjob;

/**
 * 扫描完成后的确定性规则结论：PASS 无违规，VIOLATION 有违规。
 * 两者都可对应任务 SUCCEEDED；执行成功与规则是否通过是两个维度。
 */
public enum ScanJobOutcome {
    PASS, VIOLATION
}
