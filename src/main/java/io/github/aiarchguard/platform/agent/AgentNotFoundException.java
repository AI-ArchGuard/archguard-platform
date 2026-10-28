package io.github.aiarchguard.platform.agent;

/** 统一表示请求或关联输入在当前项目范围内不可见，避免通过错误区分跨项目对象是否存在。 */
public final class AgentNotFoundException extends RuntimeException {
    public AgentNotFoundException() { super("Agent request not found"); }
}
