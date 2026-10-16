package io.github.aiarchguard.platform.agentcredential.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agentcredential.CredentialFailure;
import io.github.aiarchguard.platform.agentcredential.CredentialUseSource;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.identity.CurrentActor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class BoundedStoredCredentialUseSourceTest {
    private static final String SYNTHETIC_KEY = "synthetic-bounded-credential-only";
    private static final long MAX_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final String TRACE = "a".repeat(32);
    @TempDir Path root;

    @Test void legacyImplementationsFailClosedWithoutCallingTheirUnboundedConsumer() {
        var legacyCalls = new AtomicInteger(); var callbackCalls = new AtomicInteger();
        CredentialUseSource legacy = (version, operation) -> {
            legacyCalls.incrementAndGet(); operation.accept(SYNTHETIC_KEY);
        };
        assertUnavailable(() -> legacy.consume(UUID.randomUUID(), () -> MAX_NANOS, key -> callbackCalls.incrementAndGet()));
        assertThat(legacyCalls).hasValue(0); assertThat(callbackCalls).hasValue(0);
    }

    @Test void boundedCurrentVersionConsumesSynchronouslyAfterOnlyTheVersionIntentAudit() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger(); Thread caller = Thread.currentThread();
        fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> {
            fixture.inCallback.set(true);
            try {
                assertThat(Thread.currentThread()).isSameAs(caller);
                assertThat(key).isEqualTo(SYNTHETIC_KEY); calls.incrementAndGet();
                assertThat(fixture.audits).containsExactly(fixture.intent());
                // A completion audit would both violate the callback lock boundary and fail this call.
                fixture.failAudit.set(true);
            } finally { fixture.inCallback.remove(); }
        });
        assertThat(calls).hasValue(1); assertThat(fixture.audits).containsExactly(fixture.intent());
        assertSafeAudits(fixture);
    }

    @Test void zeroNegativeAndOverThirtySecondBudgetsNeverReachTheCallback() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        for (long remaining : new long[]{0, -1, Long.MIN_VALUE, MAX_NANOS + 1, Long.MAX_VALUE}) {
            assertUnavailable(() -> fixture.source.consume(fixture.version, () -> remaining, key -> calls.incrementAndGet()));
        }
        assertThat(calls).hasValue(0); assertSafeAudits(fixture);
    }

    @Test void throwingDeadlineIsSanitizedAndCannotConsumeOrPoisonTheSlot() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        assertUnavailable(() -> fixture.source.consume(fixture.version, () -> {
            throw new IllegalStateException(SYNTHETIC_KEY + " " + root, new IllegalArgumentException(SYNTHETIC_KEY));
        }, key -> calls.incrementAndGet()));
        assertThat(calls).hasValue(0);
        fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> {
            assertThat(key).isEqualTo(SYNTHETIC_KEY); calls.incrementAndGet();
        });
        assertThat(calls).hasValue(1); assertSafeAudits(fixture);
    }

    @Test void nullVersionDeadlineOrCallbackFailsClosed() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        assertUnavailable(() -> fixture.source.consume(null, () -> MAX_NANOS, key -> calls.incrementAndGet()));
        assertUnavailable(() -> fixture.source.consume(fixture.version, null, key -> calls.incrementAndGet()));
        assertUnavailable(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, null));
        assertThat(calls).hasValue(0); assertSafeAudits(fixture);
    }

    @Test void alreadyInterruptedCallerDoesNotConsumeAndKeepsItsInterruptFlag() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try {
            Thread.currentThread().interrupt();
            assertUnavailable(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet()));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls).hasValue(0);
        } finally { Thread.interrupted(); }
        fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet());
        assertThat(calls).hasValue(1); assertSafeAudits(fixture);
    }

    @Test void expiryDuringIntentAuditUsesTheOriginalDeadlineAndPreventsCallback() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger(); var remaining = new AtomicLong(MAX_NANOS);
        var deadlineReads = new AtomicInteger();
        fixture.onIntent.set(() -> remaining.set(0));
        assertUnavailable(() -> fixture.source.consume(fixture.version, () -> {
            deadlineReads.incrementAndGet(); return remaining.get();
        }, key -> calls.incrementAndGet()));
        assertThat(deadlineReads.get()).isGreaterThanOrEqualTo(2);
        assertThat(calls).hasValue(0); assertThat(fixture.audits).containsExactly(fixture.intent());
        fixture.onIntent.set(() -> {});
        fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet());
        assertThat(calls).hasValue(1); assertSafeAudits(fixture);
    }

    @Test void failedIntentAuditNeverReachesTheCallbackOrChangesTheVersion() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger(); fixture.failAudit.set(true);
        assertUnavailable(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet()));
        assertThat(calls).hasValue(0); assertThat(fixture.audits).isEmpty();
        fixture.failAudit.set(false);
        assertThat(new StoredCredentialVersionSource(fixture.service).current().credentialVersion()).isEqualTo(fixture.version);
    }

    @Test void unexpectedActorFailureIsSanitizedWithoutConsumptionOrAudit() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        fixture.actorFailure.set(new IllegalStateException(SYNTHETIC_KEY + root, new IllegalArgumentException("private identity")));
        assertUnavailable(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet()));
        assertThat(calls).hasValue(0); assertThat(fixture.audits).isEmpty();
        fixture.actorFailure.set(null);
        fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet());
        assertThat(calls).hasValue(1);
    }

    @Test void unexpectedConfigurationFailureIsSanitizedWithoutConsumptionOrAudit() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger(); fixture.failConfiguration.set(true);
        assertUnavailable(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet()));
        assertThat(calls).hasValue(0); assertThat(fixture.audits).isEmpty();
        fixture.failConfiguration.set(false);
        fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet());
        assertThat(calls).hasValue(1);
    }

    @Test void knownCredentialFailureCannotCarryPrivateCauseOrSuppressedDetails() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        var unsafe = new CredentialFailure(CredentialFailure.Kind.DENIED);
        unsafe.initCause(new IllegalStateException(SYNTHETIC_KEY + root));
        unsafe.addSuppressed(new IllegalArgumentException("private identity adapter"));
        fixture.actorFailure.set(unsafe);
        assertThatThrownBy(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet()))
            .isInstanceOf(CredentialFailure.class).hasMessage("credential.denied").hasNoCause()
            .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
        assertThat(calls).hasValue(0); assertThat(fixture.audits).isEmpty();
    }

    @Test void nonOwnerStillReceivesDeniedWithoutConsumption() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger(); fixture.actorId.set(UUID.randomUUID());
        assertThatThrownBy(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet()))
            .isInstanceOf(CredentialFailure.class).hasMessage("credential.denied").hasNoCause()
            .satisfies(failure -> assertThat(((CredentialFailure) failure).kind()).isEqualTo(CredentialFailure.Kind.DENIED));
        assertThat(calls).hasValue(0); assertThat(fixture.audits).hasSize(1);
        assertThat(fixture.audits.getFirst().result()).isEqualTo(AuditResult.DENIED);
    }

    @Test void boundedReentryFailsBeforeActorAuditAndDeadlineAccess() throws Exception {
        var fixture = fixture(); var nestedCalls = new AtomicInteger(); var nestedDeadlineReads = new AtomicInteger();
        fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> {
            fixture.inCallback.set(true);
            try {
                int actorReads = fixture.actorReads.get(), auditCount = fixture.audits.size();
                assertUnavailable(() -> fixture.source.consume(fixture.version, () -> {
                    nestedDeadlineReads.incrementAndGet(); return MAX_NANOS;
                }, nested -> nestedCalls.incrementAndGet()));
                assertThat(fixture.actorReads).hasValue(actorReads); assertThat(fixture.audits).hasSize(auditCount);
                assertThat(nestedDeadlineReads).hasValue(0); assertThat(nestedCalls).hasValue(0);
                assertThat(key).isEqualTo(SYNTHETIC_KEY);
            } finally { fixture.inCallback.remove(); }
        });
        assertThat(fixture.audits).containsExactly(fixture.intent()); assertSafeAudits(fixture);
    }

    @Test void legacyCallbackCannotReenterTheBoundedSourceBeforeIdentityOrAudit() throws Exception {
        var fixture = fixture(); var nestedCalls = new AtomicInteger();
        fixture.source.consume(fixture.version, key -> {
            fixture.inCallback.set(true);
            try {
                int actorReads = fixture.actorReads.get(), auditCount = fixture.audits.size();
                assertUnavailable(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, nested -> nestedCalls.incrementAndGet()));
                assertThat(fixture.actorReads).hasValue(actorReads); assertThat(fixture.audits).hasSize(auditCount);
            } finally { fixture.inCallback.remove(); }
        });
        assertThat(nestedCalls).hasValue(0); assertThat(fixture.audits).containsExactly(fixture.intent());
    }

    @Test void knownCallbackSideEffectSurvivesSafeFailureWithoutCompletionAuditOrRetry() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        assertUnavailable(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> {
            calls.incrementAndGet();
            throw new IllegalStateException(key + " " + root, new IllegalArgumentException(key));
        }));
        assertThat(calls).hasValue(1); assertThat(fixture.audits).containsExactly(fixture.intent());
        fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> {
            assertThat(key).isEqualTo(SYNTHETIC_KEY); calls.incrementAndGet();
        });
        assertThat(calls).hasValue(2);
        assertThat(fixture.audits).containsExactly(fixture.intent(), fixture.intent()); assertSafeAudits(fixture);
    }

    @Test void replacementAndDeletionRemainVisibleToTheBoundedSource() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        var replacement = fixture.service.write("synthetic-bounded-replacement-only");
        assertUnavailable(() -> fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> calls.incrementAndGet()));
        fixture.source.consume(replacement.credentialVersion(), () -> MAX_NANOS, key -> {
            assertThat(key).isEqualTo("synthetic-bounded-replacement-only"); calls.incrementAndGet();
        });
        fixture.service.delete();
        assertUnavailable(() -> fixture.source.consume(replacement.credentialVersion(), () -> MAX_NANOS, key -> calls.incrementAndGet()));
        assertThat(calls).hasValue(1); assertThat(new StoredCredentialVersionSource(fixture.service).current().configured()).isFalse();
    }

    @Test void boundedWaitTimesOutWhileLegacyCallbackStillOwnsTheSameCachedStore() throws Exception {
        var fixture = fixture();
        assertBlockedWaitEndsBeforeLegacyRelease(fixture, false, false);
    }

    @Test void boundedWaitCanBeInterruptedWhileLegacyCallbackStillOwnsTheSameCachedStore() throws Exception {
        var fixture = fixture();
        assertBlockedWaitEndsBeforeLegacyRelease(fixture, true, false);
    }

    @Test void timeSpentInIntentAuditCannotResetTheRemainingStoreWait() throws Exception {
        var fixture = fixture();
        assertBlockedWaitEndsBeforeLegacyRelease(fixture, false, true);
    }

    private void assertBlockedWaitEndsBeforeLegacyRelease(Fixture fixture, boolean interrupt, boolean shrinkDuringAudit) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(1);
        var waiterAudited = new CountDownLatch(1); var lateCallbacks = new AtomicInteger();
        var holderFailure = new AtomicReference<Throwable>(); var waiterFailure = new AtomicReference<Throwable>();
        var waiterInterrupted = new AtomicBoolean();
        var deadline = new AtomicLong();
        Thread holder = new Thread(() -> {
            try {
                fixture.source.consume(fixture.version, key -> {
                    fixture.inCallback.set(true);
                    try {
                        assertThat(key).isEqualTo(SYNTHETIC_KEY); entered.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic holder interrupted");
                    } finally { fixture.inCallback.remove(); }
                });
            } catch (Throwable failure) { holderFailure.set(failure); }
        }, "synthetic-bounded-source-holder");
        Thread waiter = new Thread(() -> {
            deadline.set(System.nanoTime() + (interrupt || shrinkDuringAudit ? MAX_NANOS : TimeUnit.SECONDS.toNanos(1)));
            try { fixture.source.consume(fixture.version, () -> deadline.get() - System.nanoTime(), key -> lateCallbacks.incrementAndGet()); }
            catch (Throwable failure) { waiterFailure.set(failure); }
            finally { waiterInterrupted.set(Thread.currentThread().isInterrupted()); done.countDown(); }
        }, "synthetic-bounded-source-waiter");
        boolean finishedBeforeRelease;
        holder.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.onIntent.set(() -> {
                if (Thread.currentThread() == waiter) {
                    if (shrinkDuringAudit) deadline.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500));
                    waiterAudited.countDown();
                }
            });
            waiter.start();
            assertThat(waiterAudited.await(5, TimeUnit.SECONDS)).as("bounded call writes its intent before waiting for the occupied slot").isTrue();
            awaitActualWait(waiter, done);
            if (interrupt) waiter.interrupt();
            finishedBeforeRelease = done.await(interrupt ? 1 : 3, TimeUnit.SECONDS);
            assertThat(finishedBeforeRelease).as("bounded caller really exits while legacy callback keeps its cached slot lock").isTrue();
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(holder.isAlive()).isTrue(); assertThat(lateCallbacks).hasValue(0);
        } finally {
            release.countDown(); holder.join(5_000); waiter.join(5_000); fixture.onIntent.set(() -> {});
        }
        assertThat(holder.isAlive()).isFalse(); assertThat(waiter.isAlive()).isFalse();
        assertThat(holderFailure.get()).isNull();
        assertThat(waiterFailure.get()).isInstanceOf(CredentialFailure.class).hasMessage("credential.unavailable").hasNoCause();
        if (interrupt) assertThat(waiterInterrupted.get()).isTrue();
        else assertThat(waiterInterrupted.get()).isFalse();
        assertThat(lateCallbacks).hasValue(0);
        assertThat(fixture.audits).containsExactly(fixture.intent(), fixture.intent()); assertSafeAudits(fixture);
        // A cancelled waiter cannot retain a callback that appears later after the legacy holder releases.
        fixture.source.consume(fixture.version, () -> MAX_NANOS, key -> {
            assertThat(key).isEqualTo(SYNTHETIC_KEY); lateCallbacks.incrementAndGet();
        });
        assertThat(lateCallbacks).hasValue(1);
    }

    private static void awaitActualWait(Thread waiter, CountDownLatch done) {
        long observationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < observationDeadline) {
            Thread.State state = waiter.getState();
            if (List.of(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING).contains(state)) return;
            assertThat(done.getCount()).as("bounded source must contend on the same store as the active legacy consumer").isEqualTo(1);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Bounded source never reached an observable credential slot wait");
    }

    private static void assertUnavailable(ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(CredentialFailure.class).hasMessage("credential.unavailable").hasNoCause()
            .satisfies(failure -> assertThat(((CredentialFailure) failure).kind()).isEqualTo(CredentialFailure.Kind.UNAVAILABLE));
    }

    private void assertSafeAudits(Fixture fixture) {
        assertThat(fixture.audits.toString()).doesNotContain(SYNTHETIC_KEY, root.toString(), "private");
    }

    private Fixture fixture() throws Exception {
        Path directory = Files.createDirectory(root.resolve("encrypted"));
        PrivateCredentialFiles.restrict(directory, true);
        Path master = Files.write(root.resolve("synthetic-master"), new byte[32]);
        PrivateCredentialFiles.restrict(master, false);
        return new Fixture(directory, master);
    }

    private static final class Fixture {
        final UUID owner = UUID.randomUUID();
        final AtomicReference<UUID> actorId = new AtomicReference<>(owner);
        final AtomicReference<RuntimeException> actorFailure = new AtomicReference<>();
        final AtomicBoolean failConfiguration = new AtomicBoolean();
        final AtomicInteger actorReads = new AtomicInteger();
        final AtomicBoolean failAudit = new AtomicBoolean();
        final AtomicReference<Runnable> onIntent = new AtomicReference<>(() -> {});
        final ThreadLocal<Boolean> inCallback = new ThreadLocal<>();
        final List<AuditEvent> audits = new CopyOnWriteArrayList<>();
        final CredentialApplicationService service;
        final CredentialUseSource source;
        final UUID version;

        Fixture(Path directory, Path master) {
            var environment = new MockEnvironment() {
                @Override public <T> T getProperty(String name, Class<T> type, T defaultValue) {
                    if (failConfiguration.get()) throw new IllegalStateException(SYNTHETIC_KEY + master,
                        new IllegalArgumentException("private configuration"));
                    return super.getProperty(name, type, defaultValue);
                }
            };
            environment.setActiveProfiles("local-compose");
            environment.withProperty("archguard.agent.credentials.enabled", "true")
                .withProperty("archguard.agent.credentials.owner-id", owner.toString())
                .withProperty("archguard.agent.credentials.origin", "http://localhost:8082")
                .withProperty("archguard.agent.credentials.allow-loopback-http", "true")
                .withProperty("archguard.agent.credentials.directory", directory.toString())
                .withProperty("archguard.agent.credentials.master-file", master.toString())
                .withProperty("archguard.agent.credentials.deployment-id", UUID.randomUUID().toString());
            service = new CredentialApplicationService(environment, () -> {
                actorReads.incrementAndGet();
                if (actorFailure.get() != null) throw actorFailure.get();
                return new CurrentActor(actorId.get(), Set.of("project:maintain"));
            }, new io.github.aiarchguard.platform.audit.AuditRecorder() {
                @Override public void record(AuditEvent event) { recordEvent(event); }
                @Override public void record(AuditEvent event, LongSupplier remaining) {
                    if (remaining == null || remaining.getAsLong() <= 0 || Thread.currentThread().isInterrupted()) {
                        throw new IllegalStateException("Synthetic bounded audit unavailable");
                    }
                    recordEvent(event);
                }
                private void recordEvent(AuditEvent event) {
                    assertThat(Boolean.TRUE.equals(inCallback.get())).as("No actor/audit module access under callback lock").isFalse();
                    if (failAudit.get()) throw new IllegalStateException("synthetic private audit failure");
                    audits.add(event);
                    if ("AGENT_CREDENTIAL_USE_REQUESTED".equals(event.action())) onIntent.get().run();
                }
            }, () -> TRACE, Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));
            version = service.write(SYNTHETIC_KEY).credentialVersion();
            audits.clear(); actorReads.set(0); source = new StoredCredentialUseSource(service);
        }

        AuditEvent intent() {
            return new AuditEvent(owner, null, "AGENT_CREDENTIAL_USE_REQUESTED", AuditResult.SUCCESS, TRACE,
                Map.of("provider", "DEEPSEEK", "credentialVersion", version.toString()));
        }
    }
}
