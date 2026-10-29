package io.github.aiarchguard.platform.governance;

/**
 * 报告字节、摘要或逻辑指纹消费失败的应用异常，HTTP 提交入口映射为 422。
 * 门禁用例捕获此类比较失败时保存 ERROR/70，不能把无法验证的报告解释为零问题。
 */
public final class InvalidGovernanceReportException extends RuntimeException {
    public InvalidGovernanceReportException(String message) { super(message); }
}
