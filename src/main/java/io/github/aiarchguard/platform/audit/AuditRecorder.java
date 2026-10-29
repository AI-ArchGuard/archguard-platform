package io.github.aiarchguard.platform.audit;

/** 审计写入公共端口；普通记录与必须遵守剩余等待预算的记录是两种显式能力。 */
public interface AuditRecorder {
    void record(AuditEvent event);

    /** 有界写入不能退化为可能无限等待的普通写入；旧实现缺少该能力时直接失败。 */
    default void record(AuditEvent event, java.util.function.LongSupplier remainingNanos) {
        throw new IllegalStateException("Bounded audit unavailable");
    }
}
