package io.github.aiarchguard.platform.agent;

public final class AgentUnavailableException extends RuntimeException {
    public AgentUnavailableException() { super("Agent storage is unavailable"); }
}
