package io.github.aiarchguard.platform.agent;

/** Fixed, non-sensitive category for an occupied daily logical request slot. */
public final class AgentDailyActionInProgressException extends RuntimeException {
    public AgentDailyActionInProgressException() { super("Agent daily action is in progress"); }
}
