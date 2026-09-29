package io.github.aiarchguard.platform.agent;

public final class AgentConflictException extends RuntimeException {
    public AgentConflictException() { super("Idempotency-Key conflicts with existing input"); }
}
