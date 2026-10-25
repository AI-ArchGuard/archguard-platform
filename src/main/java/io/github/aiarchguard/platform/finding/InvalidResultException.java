package io.github.aiarchguard.platform.finding;

/**
 * 报告大小、JSON、固定 Schema、项目身份或引用语义不合法时的内部消费错误。
 * 由上游扫描编排或报告导入流程处理；本模块的 HTTP 处置异常处理器不负责映射它。
 */
public final class InvalidResultException extends RuntimeException {
    public InvalidResultException(String message) { super(message); }
}
