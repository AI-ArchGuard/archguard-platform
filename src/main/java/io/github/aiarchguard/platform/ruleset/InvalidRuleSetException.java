package io.github.aiarchguard.platform.ruleset;

/**
 * 规则集输入不符合要求，或 Scanner 校验未能成功完成时使用的业务异常。
 * Web 层保留消息并映射为 HTTP 400；因此此类型也覆盖校验器不可用、等待超时或被中断的情况。
 */
public final class InvalidRuleSetException extends RuntimeException {
    public InvalidRuleSetException(String message) { super(message); }
}
