package io.github.aiarchguard.platform.governance;

/**
 * 治理请求元数据不满足公开输入规则时的应用异常，例如分支、幂等键、版本或例外期限非法。
 * HTTP 层返回 400 及受控校验说明；它与报告内容无法被消费的 422 分开。
 */
public final class InvalidGovernanceInputException extends RuntimeException {
    public InvalidGovernanceInputException(String message) { super(message); }
}
