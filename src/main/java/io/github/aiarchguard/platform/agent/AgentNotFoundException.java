package io.github.aiarchguard.platform.agent;

public final class AgentNotFoundException extends RuntimeException {
    public AgentNotFoundException() { super("Agent request not found"); }
}
