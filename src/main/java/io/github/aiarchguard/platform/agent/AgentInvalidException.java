package io.github.aiarchguard.platform.agent;

/** 表示 Agent 输入未满足结构或业务约束；使用固定消息，避免把不可信输入带入错误响应。 */
public final class AgentInvalidException extends RuntimeException {
    public AgentInvalidException() { super("Invalid Agent request"); }
}
