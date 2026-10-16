package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Small, dedicated executor; constructing it must neither start a thread nor initialize HTTP. */
final class PersonalLiveExecutor implements Executor, AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2), task -> {
            Thread thread = new Thread(task, "agent-personal-live-1"); thread.setDaemon(true); worker.set(thread); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final LiveAdapterBundle bundle;

    PersonalLiveExecutor(LiveAdapterBundle bundle) { this.bundle = Objects.requireNonNull(bundle); }
    @Override public void execute(Runnable task) {
        requireOpen();
        try { workers.execute(Objects.requireNonNull(task)); }
        catch (RuntimeException rejected) { throw new AgentUnavailableException(); }
    }
    void requireOpen() { if (!isOpen()) throw new AgentUnavailableException(); }
    boolean isOpen() { return !closed.get() && !workers.isShutdown(); }

    @Override public void close() {
        boolean wasInterrupted = Thread.interrupted(), interrupted = false, failed = false;
        try {
            if (closed.compareAndSet(false, true)) {
                // Remove captured queued continuations without running them; interrupt the sole owner.
                workers.shutdownNow();
                try { bundle.close(); }
                catch (RuntimeException unavailable) { failed = true; }
            }
            try { if (!awaitTermination(Duration.ofSeconds(10))) failed = true; }
            catch (InterruptedException cancelled) { interrupted = true; failed = true; }
        } finally {
            if (wasInterrupted || interrupted || Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
        }
        if (failed) throw new AgentUnavailableException();
    }

    boolean awaitTermination(Duration timeout) throws InterruptedException {
        long remaining = Objects.requireNonNull(timeout).toNanos(), started = System.nanoTime();
        if (remaining <= 0 || Thread.currentThread() == worker.get()) return false;
        if (!workers.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return false;
        long elapsed = System.nanoTime() - started;
        if (elapsed < 0 || elapsed >= remaining) return false;
        return bundle.awaitTermination(Duration.ofNanos(remaining - elapsed));
    }
}
