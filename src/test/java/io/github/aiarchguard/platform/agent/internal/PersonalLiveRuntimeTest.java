package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PersonalLiveRuntimeTest {
    @Test void defaultClosedRuntimeRejectsBeforeDispatchQueueOrAdapterInitialization() {
        var bundle = new PersonalLiveExecutorTest.FakeBundle(); var executor = new PersonalLiveExecutor(bundle);
        var dispatcherEntries = new AtomicInteger();
        var dispatcher = new LiveRequestDispatcher(null, null, null, null, null, null,
            (p, b, t, r) -> { throw new AssertionError("Default-closed runtime cannot execute"); }, executor,
            () -> { dispatcherEntries.incrementAndGet(); throw new AssertionError("Must reject before dispatcher"); });
        var runtime = new PersonalLiveRuntime(executor, dispatcher, () -> { throw new AgentUnavailableException(); });
        try {
            assertThatThrownBy(() -> runtime.dispatch(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AgentUnavailableException.class).hasNoCause();
            assertThat(dispatcherEntries).hasValue(0);
            assertThat(bundle.responses).hasValue(0); assertThat(bundle.credentials).hasValue(0);
        } finally { executor.close(); }
    }
}
