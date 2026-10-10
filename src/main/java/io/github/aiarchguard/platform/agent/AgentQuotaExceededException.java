package io.github.aiarchguard.platform.agent;

public final class AgentQuotaExceededException extends RuntimeException {
    public AgentQuotaExceededException() { super("Agent accounting quota is exhausted."); }
}
