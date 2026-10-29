package io.github.aiarchguard.platform.governance;

/**
 * 在已授权 Project/Repository 内查不到目标治理例外时的应用异常，读取或撤销入口返回 404。
 * 它表示例外资源缺失；已有例外的待生效、到期或撤销状态由视图另行表达。
 */
public final class PolicyExceptionNotFoundException extends RuntimeException { }
