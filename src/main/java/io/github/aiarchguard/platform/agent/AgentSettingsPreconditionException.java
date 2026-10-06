package io.github.aiarchguard.platform.agent;

public final class AgentSettingsPreconditionException extends RuntimeException {
    private final boolean missing;
    public AgentSettingsPreconditionException(boolean missing) { super("Agent settings precondition failed"); this.missing = missing; }
    public boolean missing() { return missing; }
}
