package io.github.aiarchguard.platform.audit;

/** 审计操作结果分类，与扫描 PASS/FAIL 和 Agent 请求状态各自独立。 */
public enum AuditResult {
    SUCCESS,
    DENIED,
    CONFLICT,
    FAILURE
}
