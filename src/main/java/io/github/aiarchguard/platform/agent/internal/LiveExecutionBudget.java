package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** One worker's monotonic allowance; an upstream supplier may tighten it, never renew it. */
final class LiveExecutionBudget implements LongSupplier {
    private static final long MAX_NANOS = Duration.ofSeconds(30).toNanos();
    private final LongSupplier nanos;
    private final LongSupplier upstream;
    private final long started;
    private final long initial;
    private long previous;

    LiveExecutionBudget(LongSupplier nanos) {
        this.nanos = Objects.requireNonNull(nanos);
        this.upstream = null;
        started = now(); initial = MAX_NANOS; previous = initial;
    }

    LiveExecutionBudget(LongSupplier nanos, LongSupplier upstream) {
        this.nanos = Objects.requireNonNull(nanos);
        this.upstream = Objects.requireNonNull(upstream);
        started = now(); initial = supplied(); previous = initial;
    }

    @Override public long getAsLong() {
        if (Thread.currentThread().isInterrupted()) return previous = 0;
        long supplied = supplied();
        long elapsed = now() - started;
        if (Thread.currentThread().isInterrupted() || elapsed < 0 || elapsed >= initial) return previous = 0;
        previous = Math.min(previous, Math.min(supplied, initial - elapsed));
        return previous;
    }

    Duration requireRemaining() {
        long remaining = getAsLong();
        if (remaining <= 0) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
        return Duration.ofNanos(remaining);
    }

    private long supplied() {
        if (Thread.currentThread().isInterrupted()) return 0;
        long value;
        try { value = upstream == null ? MAX_NANOS : upstream.getAsLong(); }
        catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
        if (value > MAX_NANOS) throw new AgentUnavailableException();
        return Math.max(0, value);
    }

    private long now() {
        try { return nanos.getAsLong(); }
        catch (RuntimeException unavailable) { throw new AgentUnavailableException(); }
    }
}
