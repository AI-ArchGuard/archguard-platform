package io.github.aiarchguard.platform.governance;

/**
 * 治理输入与既有事实冲突的应用异常，例如幂等键换输入、扫描绑定不一致或用不同原因重复撤销。
 * HTTP 层映射为 409；部分比较冲突在门禁用例中会被转换并保存为 ERROR/70。
 */
public final class GovernanceConflictException extends RuntimeException {
    public GovernanceConflictException(String message) { super(message); }
}
