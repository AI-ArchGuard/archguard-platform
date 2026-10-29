package io.github.aiarchguard.platform.agent;

/** 表示当前开关、确认、批次或版本绑定不再满足操作条件；不携带失效凭据或提交正文。 */
public final class AgentEnablementInvalidStateException extends RuntimeException {
    public AgentEnablementInvalidStateException() { super("Agent enablement is not valid for this operation"); }
}
