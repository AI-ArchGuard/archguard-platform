package io.github.aiarchguard.platform.agentcredential.internal;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Local elapsed time can only shorten the caller's original remaining budget, never restart it. */
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
