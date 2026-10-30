package io.github.aiarchguard.platform.agent;

/** 日常授权状态拒绝只携带固定错误，不泄漏内部绑定或配置。 */
public final class AgentDailyStateInvalidException extends RuntimeException {
    public AgentDailyStateInvalidException() { super("Agent daily state is not valid for this operation"); }
}
