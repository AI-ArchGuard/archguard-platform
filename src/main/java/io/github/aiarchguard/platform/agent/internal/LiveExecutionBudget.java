package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 一个 worker 的单调剩余时间预算；上游只能收紧期限，不能续期，线程中断立即耗尽。
 * 检查点不能单独证明任意底层 I/O 都能即时取消。
 */
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

    /** 取历史最小剩余值、上游剩余值及本地初始预算减耗时的最小值；中断或时钟异常不允许扩大执行窗口。 */
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
