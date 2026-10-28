package io.github.aiarchguard.platform.agent;

/** Agent 存储或受控执行条件不可用时使用的固定异常，向外隐藏底层数据库和适配器详情。 */
public final class AgentUnavailableException extends RuntimeException {
    public AgentUnavailableException() { super("Agent storage is unavailable"); }
}
