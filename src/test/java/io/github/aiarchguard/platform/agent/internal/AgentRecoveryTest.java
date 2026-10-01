package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class AgentRecoveryTest {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Instant> selectedCutoff = new AtomicReference<>();
    private boolean unavailable;
    private final AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
        new Class<?>[] {AgentStore.class}, (proxy, method, args) -> {
            if (!method.getName().equals("expired")) throw new AssertionError("Unexpected store write: " + method.getName());
            calls.incrementAndGet();
            selectedCutoff.set((Instant) args[0]);
            assertThat(args[1]).isEqualTo(100);
            if (unavailable) throw new IllegalStateException("synthetic database outage");
            return List.of();
        });
    private final AgentSchemaAvailability schema = new AgentSchemaAvailability(() -> {});
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    private final AgentRecovery recovery = new AgentRecovery(store,
        new AgentTransitions(store, null, event -> { throw new AssertionError("Unexpected audit write"); }, clock), schema, clock);

    @Test void schemaFailureSkipsRecoveryAndDatabaseFailureDoesNotEscape() {
        recovery.recoverExpired();
        assertThat(calls.get()).isZero();
        schema.migrateOnReady();
        unavailable = true;
        assertThatCode(recovery::recoverExpired).doesNotThrowAnyException();
        assertThat(calls.get()).isOne();
    }

    @Test void selectionIsBoundedAndUsesHardTimeoutWithGrace() {
        schema.migrateOnReady();
        Instant cutoff = Instant.parse("2026-09-30T23:59:00Z");
        recovery.recoverExpired();
        assertThat(calls.get()).isOne();
        assertThat(selectedCutoff.get()).isEqualTo(cutoff);
    }
}
