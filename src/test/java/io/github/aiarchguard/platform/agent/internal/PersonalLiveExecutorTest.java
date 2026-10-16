package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PersonalLiveExecutorTest {
    @Test void closeStopsInFlightDiscardsQueuedWorkAndReallyAwaitsBothResources() throws Exception {
        var bundle = new FakeBundle(); var executor = new PersonalLiveExecutor(bundle);
        var entered = new CountDownLatch(1); var exited = new CountDownLatch(1);
        var queuedCalls = new AtomicInteger(); var interrupted = new AtomicBoolean();
        try {
            executor.execute(() -> {
                entered.countDown();
                try { bundle.release.await(); }
                catch (InterruptedException cancelled) { interrupted.set(true); }
                finally { exited.countDown(); }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(queuedCalls::incrementAndGet); executor.execute(queuedCalls::incrementAndGet);
            executor.close();
            assertThat(executor.isOpen()).isFalse(); assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue(); assertThat(queuedCalls).hasValue(0);
            assertThat(bundle.closes).hasValue(1); assertThat(bundle.awaited).isTrue();
            assertThat(executor.awaitTermination(Duration.ofSeconds(1))).isTrue();
            assertThatThrownBy(() -> executor.execute(() -> { throw new AssertionError("closed queue cannot run"); }))
                .isInstanceOf(AgentUnavailableException.class).hasNoCause();
            executor.close(); assertThat(bundle.closes).hasValue(1);
        } finally { executor.close(); }
    }

    @Test void constructionDoesNotInitializeAdapterOrStartWorkAndQueueIsBounded() throws Exception {
        var bundle = new FakeBundle(); var executor = new PersonalLiveExecutor(bundle);
        var entered = new CountDownLatch(1);
        try {
            assertThat(bundle.responses).hasValue(0); assertThat(bundle.credentials).hasValue(0);
            executor.execute(() -> { entered.countDown(); try { bundle.release.await(); } catch (InterruptedException ignored) { } });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {}); executor.execute(() -> {});
            assertThatThrownBy(() -> executor.execute(() -> { throw new AssertionError("queue overflow cannot execute"); }))
                .isInstanceOf(AgentUnavailableException.class).hasNoCause();
            assertThat(bundle.responses).hasValue(0); assertThat(bundle.credentials).hasValue(0);
        } finally { executor.close(); }
    }

    @Test void cleanupPreservesCallerInterruptAndClosesUnusedBundle() {
        var bundle = new FakeBundle(); var executor = new PersonalLiveExecutor(bundle);
        Thread.currentThread().interrupt();
        try {
            executor.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(bundle.closes).hasValue(1); assertThat(bundle.awaited).isTrue();
            assertThat(executor.isOpen()).isFalse();
        } finally { Thread.interrupted(); executor.close(); }
    }

    static final class FakeBundle implements LiveAdapterBundle {
        final AtomicInteger responses = new AtomicInteger(), credentials = new AtomicInteger(), closes = new AtomicInteger();
        final AtomicBoolean awaited = new AtomicBoolean(); final CountDownLatch release = new CountDownLatch(1);
        @Override public LiveResponsePort responses() { responses.incrementAndGet(); throw new AssertionError("No adapter initialization"); }
        @Override public LiveExecutionCoordinator.CredentialUse credentials() { credentials.incrementAndGet(); throw new AssertionError("No credential consumption"); }
        @Override public void close() { closes.incrementAndGet(); release.countDown(); }
        @Override public boolean awaitTermination(Duration timeout) { awaited.set(true); return closes.get() > 0; }
    }
}
