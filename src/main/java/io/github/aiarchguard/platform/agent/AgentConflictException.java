package io.github.aiarchguard.platform.agent;

/** 幂等键绑定不同输入或真实模板槽位已占用时抛出；HTTP 层映射 409，防止重放改输入或换键补发。 */
public final class AgentConflictException extends RuntimeException {
    public AgentConflictException() { super("Idempotency-Key conflicts with existing input"); }
}
