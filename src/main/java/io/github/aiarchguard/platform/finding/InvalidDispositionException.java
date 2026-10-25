package io.github.aiarchguard.platform.finding;

/** 查询分页或人工处置输入不符合应用约束时抛出，由 Web 层映射为带 traceId 的 400 错误。 */
public final class InvalidDispositionException extends RuntimeException {
    public InvalidDispositionException(String message) { super(message); }
}
