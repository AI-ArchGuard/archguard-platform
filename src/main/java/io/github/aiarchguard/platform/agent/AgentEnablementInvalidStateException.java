package io.github.aiarchguard.platform.agent;

public final class AgentEnablementInvalidStateException extends RuntimeException {
    public AgentEnablementInvalidStateException() { super("Agent enablement is not valid for this operation"); }
}
