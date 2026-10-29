package io.github.aiarchguard.platform.agent;

/** 表示真实账本的批次或日额度不足；显式 live HTTP 入口将其映射为限额响应，不触发自动重试。 */
public final class AgentQuotaExceededException extends RuntimeException {
    public AgentQuotaExceededException() { super("Agent accounting quota is exhausted."); }
}
