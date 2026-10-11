package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.identity.CurrentActor;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class LiveExecutionCoordinatorTest {
    static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    static final String INSUFFICIENT = """
        {"schemaVersion":"0.1.0","purpose":"FINDING_EXPLANATION",
         "conclusion":{"kind":"INSUFFICIENT","text":null,"citationIds":[]},
         "claims":[],"ruleBasis":[],"suggestions":[],"limitations":["Synthetic evidence only"]}
        """;
    final UUID project = UUID.randomUUID(), batch = UUID.randomUUID(), template = UUID.randomUUID(), request = UUID.randomUUID(), owner = UUID.randomUUID();
    MemoryRequests requests;
    Ledger accounting;
    Port provider;
    AtomicLong nanos;
    AtomicInteger credentialReads, resourceChecks;
    List<AuditEvent> audit;
    AgentSnapshot original;

    @BeforeEach void setup() {
        var input = new AgentModelPort.ModelInput("FINDING_EXPLANATION", "0.1.0", LiveAccountingOperations.EXPLANATION_PROMPT,
            "c-finding", "synthetic.rule", "0.1.0", "high", "Synthetic evidence", "synthetic.module", List.of(), List.of(), null);
        var bindings = new AgentRequestView.VersionBindings(UUID.randomUUID(), "a".repeat(64), null, List.of(UUID.randomUUID()),
            List.of(), input.promptVersion(), LiveAccountingOperations.MODEL_PROFILE, "responses-v1-restricted", "deepseek-flash",
            "0.1.0", LiveCostPolicy.VERSION, "b".repeat(64));
        var view = new AgentRequestView(request, project, owner, "c".repeat(32), input.purpose(), "RUNNING", bindings, null, null, null, NOW, NOW);
        original = new AgentSnapshot(view, input, List.of());
        requests = new MemoryRequests(original); accounting = new Ledger(); provider = new Port(); nanos = new AtomicLong();
        credentialReads = new AtomicInteger(); resourceChecks = new AtomicInteger(); audit = new CopyOnWriteArrayList<>();
    }
    LiveResultTransitions writer() {
        return new LiveResultTransitions(requests, audit::add, Clock.fixed(NOW, ZoneOffset.UTC), new MemoryTransactions());
    }
    LiveExecutionCoordinator core(Consumer<AgentSnapshot> resources) {
        return new LiveExecutionCoordinator(requests, accounting, resources, provider, (version, operation) -> {
            assertThat(version).isEqualTo(accounting.credential); credentialReads.incrementAndGet();
            return operation.apply("synthetic-placeholder-not-a-key");
        }, writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()), body -> {}, nanos::get);
    }
    void execute(LiveExecutionCoordinator core) { core.execute(project, batch, template, request); }
    Consumer<AgentSnapshot> validResources() { return snapshot -> resourceChecks.incrementAndGet(); }

    @Test void publicConstructionAlwaysDeniesBeforeReservationSecretAndHttp() {
        var resources = new LiveResourceAuthorizer(unusedPort(ProjectAuthorization.class),
            unusedPort(ScanJobOperations.class), unusedPort(FindingOperations.class),
            unusedPort(DocumentOperations.class), unusedPort(ReportSubmissionOperations.class));
        var core = new LiveExecutionCoordinator(requests, accounting, resources, provider, (version, operation) -> {
            credentialReads.incrementAndGet(); throw new AssertionError("Credential must not be consumed");
        }, writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()));
        assertThatThrownBy(() -> execute(core)).isInstanceOf(AgentUnavailableException.class)
            .hasMessage("Agent storage is unavailable").hasNoCause();
        assertThat(accounting.reserves.get()).isZero(); assertThat(credentialReads.get()).isZero(); assertThat(provider.sends.get()).isZero();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(requests.value.view().failure()).isNull(); assertThat(audit).isEmpty();
        assertThat(accounting.unknown.get()).isZero(); assertThat(accounting.settled.get()).isZero();
    }

    @Test void validUsageIsSettledBeforeValidatedResultAndOnlyRealResultAuditIsWritten() {
        execute(core(validResources()));
        assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED");
        assertThat(accounting.settled.get()).isOne(); assertThat(accounting.unknown.get()).isZero();
        assertThat(provider.sends.get()).isOne(); assertThat(credentialReads.get()).isOne(); assertThat(resourceChecks.get()).isEqualTo(4);
        assertThat(requests.value.view().usage().actualCostMicrousd()).isEqualTo(273);
        assertThat(requests.value.input()).isSameAs(original.input()); assertThat(requests.value.view().bindings()).isSameAs(original.view().bindings());
        assertThat(audit).hasSize(1); assertThat(audit.getFirst().action()).isEqualTo("agent.live.result_succeeded");
        assertThat(audit.toString()).doesNotContain("Synthetic evidence", "synthetic-placeholder", INSUFFICIENT);
    }

    @Test void rejectedOutputAndForgedReferencesStillSettleKnownUsage() {
        provider.reply = new LiveResponsePort.Reply(null, "OUTPUT_INVALID", provider.reply.usage(), "resp-synthetic", "deepseek-flash");
        execute(core(validResources()));
        assertThat(requests.value.view().failure().code()).isEqualTo("OUTPUT_INVALID");
        assertThat(requests.value.view().usage().actualCostMicrousd()).isEqualTo(273); assertThat(accounting.unknown.get()).isZero();
    }

    @Test void schemaFailureDoesNotRefundKnownUsage() {
        provider.reply = new LiveResponsePort.Reply("{}", null, provider.reply.usage(), "resp-synthetic", "deepseek-flash");
        execute(core(validResources()));
        assertThat(requests.value.view().failure().code()).isEqualTo("OUTPUT_INVALID"); assertThat(accounting.settled.get()).isOne();
        assertThat(requests.value.view().result()).isNull(); assertThat(accounting.unknown.get()).isZero();
    }

    @Test void authorizationRevokedBeforeDisplayCannotEraseCost() {
        execute(core(snapshot -> {
            if (resourceChecks.incrementAndGet() == 4) throw new ProjectPermissionDeniedException();
        }));
        assertThat(requests.value.view().failure().code()).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(accounting.settled.get()).isOne(); assertThat(accounting.unknown.get()).isZero(); assertThat(requests.value.view().result()).isNull();
    }

    @Test void noAuthorizationBeforeReservationProducesZeroSecretHttpAndLedger() {
        assertThatThrownBy(() -> execute(core(snapshot -> { throw new ProjectPermissionDeniedException(); })))
            .isInstanceOf(ProjectPermissionDeniedException.class).hasNoCause();
        assertThat(accounting.reserves.get()).isZero(); assertThat(credentialReads.get()).isZero(); assertThat(provider.sends.get()).isZero();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING"); assertThat(audit).isEmpty();
    }

    @Test void concurrentReplayPrepareFailureCannotTerminalizeOwningExecutor() throws Exception {
        LiveResponsePort rejectedPrepare = new LiveResponsePort() {
            public byte[] prepare(AgentModelPort.ModelInput input) {
                throw new IllegalArgumentException("private synthetic body", new IllegalStateException("private cause"));
            }
            public Reply send(byte[] body, String key, Duration timeout, Set<String> models) {
                throw new AssertionError("Preflight replay must never send");
            }
        };
        assertConcurrentPreflightFailure(rejectedPrepare, body -> {}, validResources(), AgentUnavailableException.class);
    }

    @Test void concurrentReplayPolicyFailureCannotTerminalizeOwningExecutor() throws Exception {
        assertConcurrentPreflightFailure(provider, body -> {
            throw new IllegalStateException("private policy detail", new IllegalArgumentException("private cause"));
        }, validResources(), AgentUnavailableException.class);
    }

    @Test void concurrentReplayAuthorizationFailureCannotTerminalizeOwningExecutor() throws Exception {
        assertConcurrentPreflightFailure(provider, body -> {}, snapshot -> { throw new ProjectNotFoundException(); },
            ProjectPermissionDeniedException.class);
    }

    @Test void ordinaryProjectNotFoundAfterReservationMapsToAuthorizationRevoked() {
        execute(core(snapshot -> {
            if (resourceChecks.incrementAndGet() == 2) throw new ProjectNotFoundException();
        }));
        assertThat(requests.value.view().failure().code()).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(accounting.reserves.get()).isOne(); assertThat(accounting.unknown.get()).isOne();
        assertThat(provider.sends.get()).isZero(); assertThat(credentialReads.get()).isZero();
    }

    @Test void ordinaryProjectNotFoundBeforeDisplayPreservesKnownSettlement() {
        execute(core(snapshot -> {
            if (resourceChecks.incrementAndGet() == 4) throw new ProjectNotFoundException();
        }));
        assertThat(requests.value.view().failure().code()).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(accounting.settled.get()).isOne(); assertThat(accounting.unknown.get()).isZero();
        assertThat(requests.value.view().usage().actualCostMicrousd()).isEqualTo(273);
        assertThat(requests.value.view().result()).isNull();
    }

    @Test void forgedCitationFailureDoesNotRefundKnownSettlement() {
        provider.reply = new LiveResponsePort.Reply("""
            {"schemaVersion":"0.1.0","purpose":"FINDING_EXPLANATION",
             "conclusion":{"kind":"SUPPORTED","text":"Synthetic explanation","citationIds":["c-forged"]},
             "claims":[{"findingRef":"c-finding","text":"Synthetic claim","citationIds":["c-forged"]}],
             "ruleBasis":[{"findingRef":"c-finding","text":"Synthetic rule","citationIds":["c-forged"]}],
             "suggestions":[],"limitations":["Synthetic evidence only"]}
            """, null, provider.reply.usage(), "resp-synthetic", "deepseek-flash");
        execute(core(validResources()));
        assertThat(requests.value.view().failure().code()).isEqualTo("CITATION_INVALID");
        assertThat(accounting.settled.get()).isOne(); assertThat(accounting.unknown.get()).isZero();
        assertThat(requests.value.view().usage().actualCostMicrousd()).isEqualTo(273);
        assertThat(requests.value.view().result()).isNull();
    }

    @Test void resultAuditFailureCannotEraseIndependentKnownSettlement() {
        // Model transaction rollback only for the result store; the ledger is independently committed.
        var failingWriter = new LiveResultTransitions(requests, event -> {
            throw new IllegalStateException("private audit detail");
        }, Clock.fixed(NOW, ZoneOffset.UTC), new RollbackResultTransactions(requests));
        var core = new LiveExecutionCoordinator(requests, accounting, validResources(), provider,
            (version, operation) -> operation.apply("synthetic-placeholder-not-a-key"), failingWriter,
            new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()), body -> {}, nanos::get);
        assertThatThrownBy(() -> execute(core)).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(accounting.settled.get()).isOne(); assertThat(accounting.unknown.get()).isZero();
        assertThat(provider.sends.get()).isOne();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(requests.value.view().result()).isNull(); assertThat(audit).isEmpty();
    }

    @Test void withdrawalAfterReservationAndInsideCredentialCallbackPreventsHttp() {
        accounting.rejectCheck = 2;
        execute(core(validResources()));
        assertThat(accounting.unknown.get()).isOne(); assertThat(accounting.settled.get()).isZero(); assertThat(provider.sends.get()).isZero();
    }

    @Test void invalidUsageAndProviderFailureKeepReservationAndNeverRetry() {
        provider.reply = new LiveResponsePort.Reply(null, null, new LiveTokenUsage(8001, 0, 0, 8001, 0), "resp-synthetic", "deepseek-flash");
        execute(core(validResources())); execute(core(validResources()));
        assertThat(accounting.unknown.get()).isOne(); assertThat(provider.sends.get()).isOne(); assertThat(accounting.settled.get()).isZero();
    }

    @Test void transportTimeoutIsSanitizedRetainsUnknownAndDoesNotRetry() {
        provider.failure = new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
        execute(core(validResources())); execute(core(validResources()));
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_TIMEOUT"); assertThat(accounting.unknown.get()).isOne();
        assertThat(provider.sends.get()).isOne();
    }

    @Test void lockWaitExhaustsSingleDeadlineAndLateReplyIsStillSettledButNotDisplayed() {
        accounting.onReserve = () -> nanos.set(Duration.ofSeconds(30).toNanos());
        execute(core(validResources()));
        assertThat(provider.sends.get()).isZero(); assertThat(credentialReads.get()).isZero(); assertThat(accounting.unknown.get()).isOne();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_TIMEOUT");
    }

    @Test void transportGetsRemainingDeadlineAndLateKnownReplyIsCharged() {
        accounting.onReserve = () -> nanos.set(Duration.ofSeconds(8).toNanos());
        provider.onSend = () -> nanos.set(Duration.ofSeconds(31).toNanos());
        execute(core(validResources()));
        assertThat(provider.timeout).isEqualTo(Duration.ofSeconds(22)); assertThat(accounting.settled.get()).isOne();
        assertThat(accounting.unknown.get()).isZero(); assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_TIMEOUT");
    }

    @Test void providerLatencyStopsAtResponseRatherThanIncludingSettlement() {
        provider.onSend = () -> nanos.set(Duration.ofSeconds(2).toNanos());
        accounting.onSettle = () -> nanos.set(Duration.ofSeconds(10).toNanos());
        execute(core(validResources()));
        assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED");
        assertThat(requests.value.view().usage().providerLatencyMs()).isEqualTo(2_000);
        assertThat(requests.value.view().usage().latencyMs()).isEqualTo(10_000);
        assertThat(accounting.settled.get()).isOne(); assertThat(accounting.unknown.get()).isZero();
    }

    @Test void observedLatencyIncludesFinalOutputAndResourceValidation() {
        provider.onSend = () -> nanos.set(Duration.ofSeconds(2).toNanos());
        execute(core(snapshot -> {
            if (resourceChecks.incrementAndGet() == 4) nanos.set(Duration.ofSeconds(5).toNanos());
        }));
        assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED");
        assertThat(requests.value.view().usage().providerLatencyMs()).isEqualTo(2_000);
        assertThat(requests.value.view().usage().latencyMs()).isEqualTo(5_000);
    }

    @Test void concurrentReplaySendsExactlyOnceAndCannotTerminalizeOriginal() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        provider.onSend = () -> {
            entered.countDown();
            try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> execute(core(validResources())));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                executor.submit(() -> execute(core(validResources()))).get(5, TimeUnit.SECONDS);
                assertThat(requests.value.view().state()).isEqualTo("RUNNING"); assertThat(provider.sends.get()).isOne();
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED"); assertThat(accounting.settled.get()).isOne();
    }

    @Test void recoveryTerminalStateIsNeverOverwrittenButKnownChargeSurvives() {
        provider.onSend = () -> requests.failRunning(request, new AgentRequestView.AgentFailure("INTERNAL_ERROR", "Recovered"), NOW);
        execute(core(validResources()));
        assertThat(requests.value.view().failure().message()).isEqualTo("Recovered"); assertThat(accounting.settled.get()).isOne(); assertThat(audit).isEmpty();
    }

    @Test void wrongActorCannotReadSecretSendOrMutateRequest() {
        var core = new LiveExecutionCoordinator(requests, accounting, validResources(), provider, (version, operation) -> {
            throw new AssertionError();
        }, writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(UUID.randomUUID(), Set.of()), body -> {}, nanos::get);
        assertThatThrownBy(() -> execute(core)).isInstanceOf(ProjectPermissionDeniedException.class);
        assertThat(requests.value.view().state()).isEqualTo("RUNNING"); assertThat(accounting.reserves.get()).isZero();
    }

    @Test void credentialConsumerCannotSendTwice() {
        var core = new LiveExecutionCoordinator(requests, accounting, validResources(), provider, (version, operation) -> {
            operation.apply("synthetic-placeholder-not-a-key"); return operation.apply("synthetic-placeholder-not-a-key");
        }, writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()), body -> {}, nanos::get);
        execute(core); assertThat(provider.sends.get()).isOne(); assertThat(accounting.unknown.get()).isOne();
    }

    private void assertConcurrentPreflightFailure(LiveResponsePort replayResponses,
            LiveExecutionCoordinator.SendPolicy replayPolicy, Consumer<AgentSnapshot> replayResources,
            Class<? extends Throwable> safeFailure) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        provider.onSend = () -> {
            entered.countDown();
            try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        };
        var replay = new LiveExecutionCoordinator(requests, accounting, replayResources, replayResponses,
            (version, operation) -> { throw new AssertionError("Replay must not consume credentials"); },
            writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()),
            replayPolicy, nanos::get);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> first = executor.submit(() -> execute(core(validResources())));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                Throwable replayFailure = catchThrowable(() -> execute(replay));
                assertThat(requests.value.view().state()).isEqualTo("RUNNING");
                assertThat(requests.value.view().failure()).isNull(); assertThat(audit).isEmpty();
                assertThat(accounting.reserves.get()).isOne(); assertThat(accounting.unknown.get()).isZero();
                assertThat(accounting.settled.get()).isZero(); assertThat(provider.sends.get()).isOne();
                assertThat(credentialReads.get()).isOne();
                assertThat(replayFailure).isInstanceOf(safeFailure).hasNoCause().hasMessageNotContaining("private");
            } finally {
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED");
        assertThat(accounting.reserves.get()).isOne(); assertThat(accounting.settled.get()).isOne();
        assertThat(accounting.unknown.get()).isZero(); assertThat(provider.sends.get()).isOne();
        assertThat(audit).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static <T> T unusedPort(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            throw new AssertionError("Default egress rejection must precede resource access");
        });
    }

    static final class Port implements LiveResponsePort {
        final AtomicInteger sends = new AtomicInteger();
        volatile Reply reply = new Reply(INSUFFICIENT, null, new LiveTokenUsage(1000, 100, 500, 1100, 0), "resp-synthetic", "deepseek-flash");
        Runnable onSend = () -> {}; RuntimeException failure; Duration timeout;
        public byte[] prepare(AgentModelPort.ModelInput input) { return new byte[]{1}; }
        public Reply send(byte[] body, String key, Duration remaining, Set<String> models) {
            sends.incrementAndGet(); timeout = remaining; onSend.run(); if (failure != null) throw failure; return reply;
        }
    }
    static final class Ledger implements LiveAccountingOperations {
        final UUID credential = UUID.randomUUID(); final AtomicBoolean owns = new AtomicBoolean();
        final AtomicInteger reserves = new AtomicInteger(), settled = new AtomicInteger(), unknown = new AtomicInteger(), checks = new AtomicInteger();
        Runnable onReserve = () -> {}, onSettle = () -> {}; int rejectCheck;
        public Admission reserve(UUID p, UUID b, UUID t, UUID r) { reserves.incrementAndGet(); onReserve.run(); return new Admission(r, 4200, owns.compareAndSet(false, true)); }
        public SendApproval checkBeforeSend(UUID p, UUID b, UUID t, UUID r) {
            if (checks.incrementAndGet() == rejectCheck) throw new AgentEnablementInvalidStateException();
            return new SendApproval(credential, Set.of("deepseek-flash"));
        }
        public Outcome settle(UUID p, UUID r, String price, LiveTokenUsage usage) { settled.incrementAndGet(); onSettle.run(); return new Outcome("SETTLED", LiveCostPolicy.cost(usage)); }
        public Outcome unknown(UUID p, UUID r) { unknown.incrementAndGet(); return new Outcome("UNKNOWN", null); }
        public String preview(UUID p, List<UUID> templates) { throw new AssertionError(); }
        public LiveBatchView approve(UUID p, String o, boolean s, LiveBatchInput i) { throw new AssertionError(); }
        public LiveBatchView get(UUID p, UUID b) { throw new AssertionError(); }
        public LiveBatchView revoke(UUID p, UUID b, String o, boolean s) { throw new AssertionError(); }
        public String requestDigest(UUID p, UUID b, UUID t) { throw new AssertionError(); }
    }
    static final class MemoryRequests implements AgentStore {
        volatile AgentSnapshot value;
        MemoryRequests(AgentSnapshot value) { this.value = value; }
        public Optional<AgentSnapshot> find(UUID p, UUID r) { return value.view().projectId().equals(p) && value.view().id().equals(r) ? Optional.of(value) : Optional.empty(); }
        public synchronized boolean finish(UUID r, AgentRequestView.AgentResult result, AgentRequestView.AgentFailure failure, AgentRequestView.AgentUsage usage, Instant at) {
            var v = value.view(); if (!v.id().equals(r) || !"RUNNING".equals(v.state())) return false;
            value = new AgentSnapshot(new AgentRequestView(v.id(), v.projectId(), v.requesterId(), v.traceId(), v.purpose(), result == null ? "FAILED" : "SUCCEEDED",
                v.bindings(), result, failure, usage, v.createdAt(), at), value.input(), value.candidates()); return true;
        }
        public boolean failRunning(UUID r, AgentRequestView.AgentFailure failure, Instant at) { return finish(r, null, failure, null, at); }
        public boolean insert(AgentSnapshot s, String key) { throw new AssertionError(); }
        public Optional<AgentSnapshot> findByKey(UUID p, UUID a, String key) { throw new AssertionError(); }
        public boolean claim(UUID r, Instant at) { throw new AssertionError(); }
        public void setReservation(UUID r, LocalDate day, long amount) { throw new AssertionError(); }
        public boolean failQueued(UUID r, AgentRequestView.AgentFailure failure, Instant at) { throw new AssertionError(); }
        public boolean hasRequests(UUID p) { throw new AssertionError(); }
        public List<AgentSnapshot> expired(Instant cutoff, int limit) { throw new AssertionError(); }
        public boolean expire(UUID r, Instant cutoff, AgentRequestView.AgentFailure failure, Instant at) { throw new AssertionError(); }
    }
    static final class MemoryTransactions extends AbstractPlatformTransactionManager {
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) {}
        protected void doRollback(DefaultTransactionStatus status) {}
    }

    static final class RollbackResultTransactions extends AbstractPlatformTransactionManager {
        private final MemoryRequests requests;
        private AgentSnapshot before;
        RollbackResultTransactions(MemoryRequests requests) { this.requests = requests; }
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) { before = requests.value; }
        protected void doCommit(DefaultTransactionStatus status) {}
        protected void doRollback(DefaultTransactionStatus status) { requests.value = before; }
    }
}
