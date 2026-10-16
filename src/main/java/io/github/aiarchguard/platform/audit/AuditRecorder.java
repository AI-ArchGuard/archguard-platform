package io.github.aiarchguard.platform.audit;

public interface AuditRecorder {
    void record(AuditEvent event);

    /** Explicit bounded persistence; legacy recorders must not silently fall back to unbounded work. */
    default void record(AuditEvent event, java.util.function.LongSupplier remainingNanos) {
        throw new IllegalStateException("Bounded audit unavailable");
    }
}
