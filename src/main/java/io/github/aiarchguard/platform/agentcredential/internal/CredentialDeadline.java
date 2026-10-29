package io.github.aiarchguard.platform.agentcredential.internal;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 本地实际耗时只能缩短调用方原预算；即使供应器返回常量，也不能重启或延长执行期限。 */
final class CredentialDeadline {
    private static final long MAX_NANOS = Duration.ofSeconds(30).toNanos();
    private final LongSupplier remaining;
    private final long started;
    private final long initial;

    CredentialDeadline(LongSupplier remaining) {
        started = System.nanoTime();
        this.remaining = remaining;
        initial = supplied();
        remainingNanos();
    }

    /** 取供应器预算与本地剩余值的较小者，过期、中断或非法供应器均安全失败。 */
    long remainingNanos() {
        long supplied = supplied();
        long elapsed = System.nanoTime() - started;
        if (elapsed < 0 || elapsed >= initial) throw PrivateCredentialFiles.unavailable();
        return Math.min(supplied, initial - elapsed);
    }

    private long supplied() {
        if (remaining == null || Thread.currentThread().isInterrupted()) throw PrivateCredentialFiles.unavailable();
        try {
            long value = remaining.getAsLong();
            if (value <= 0 || value > MAX_NANOS || Thread.currentThread().isInterrupted()) throw PrivateCredentialFiles.unavailable();
            return value;
        } catch (RuntimeException invalid) { throw PrivateCredentialFiles.unavailable(); }
    }
}
