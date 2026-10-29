package io.github.aiarchguard.platform.agent;

/** 区分缺少 If-Match 与设置修订号过期，让 HTTP 层分别返回前置条件缺失或失败，避免并发覆盖设置。 */
public final class AgentSettingsPreconditionException extends RuntimeException {
    private final boolean missing;
    public AgentSettingsPreconditionException(boolean missing) { super("Agent settings precondition failed"); this.missing = missing; }
    public boolean missing() { return missing; }
}
