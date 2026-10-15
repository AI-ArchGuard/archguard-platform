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
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    LiveDatabaseWork database;

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
        database = memoryDatabaseWork(new MemoryTransactions());
    }
    LiveResultTransitions writer() {
        return new LiveResultTransitions(requests, audit::add, Clock.fixed(NOW, ZoneOffset.UTC), new MemoryTransactions(), database);
    }
    LiveExecutionCoordinator core(Consumer<AgentSnapshot> resources) {
        return new LiveExecutionCoordinator(requests, accounting, resources, provider, boundedFake((version, preflight, operation) -> {
            preflight.run();
            assertThat(version).isEqualTo(accounting.credential); credentialReads.incrementAndGet();
            return operation.apply("synthetic-placeholder-not-a-key");
        }), writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()), body -> {}, nanos::get, database);
    }
    void execute(LiveExecutionCoordinator core) { core.execute(project, batch, template, request); }
    Consumer<AgentSnapshot> validResources() { return snapshot -> resourceChecks.incrementAndGet(); }

    @Test void publicConstructionAlwaysDeniesBeforeReservationSecretAndHttp() {
        var resources = new LiveResourceAuthorizer(unusedPort(ProjectAuthorization.class),
            unusedPort(ScanJobOperations.class), unusedPort(FindingOperations.class),
            unusedPort(DocumentOperations.class), unusedPort(ReportSubmissionOperations.class));
        var core = new LiveExecutionCoordinator(requests, accounting, resources, provider, (version, preflight, operation) -> {
            credentialReads.incrementAndGet(); throw new AssertionError("Credential must not be consumed");
        }, writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()));
        assertThatThrownBy(() -> execute(core)).isInstanceOf(AgentUnavailableException.class)
            .hasMessage("Agent storage is unavailable").hasNoCause();
        assertThat(accounting.reserves.get()).isZero(); assertThat(credentialReads.get()).isZero(); assertThat(provider.sends.get()).isZero();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(requests.value.view().failure()).isNull(); assertThat(audit).isEmpty();
        assertThat(accounting.unknown.get()).isZero(); assertThat(accounting.settled.get()).isZero();
    }

    @Test void explicitDatabaseOnPublicConstructionStillCannotAuthorizeEgress() {
        var resources = new LiveResourceAuthorizer(unusedPort(ProjectAuthorization.class),
            unusedPort(ScanJobOperations.class), unusedPort(FindingOperations.class),
            unusedPort(DocumentOperations.class), unusedPort(ReportSubmissionOperations.class));
        var core = new LiveExecutionCoordinator(requests, accounting, resources, provider,
            (version, preflight, operation) -> { throw new AssertionError("No credential use is authorized"); },
            writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()), database);
        assertThatThrownBy(() -> execute(core)).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(accounting.reserves.get()).isZero(); assertThat(provider.sends.get()).isZero();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING"); assertThat(audit).isEmpty();
    }

    @Test void unsupportedDatabaseCannotExecuteReadsOrReserveSendOrMutate() {
        database = new LiveDatabaseWork() {};
        assertThatThrownBy(() -> execute(core(validResources()))).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(accounting.reserves.get()).isZero(); assertThat(accounting.checks.get()).isZero();
        assertThat(resourceChecks.get()).isZero(); assertThat(provider.sends.get()).isZero();
        assertThat(accounting.unknown.get()).isZero(); assertThat(requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(audit).isEmpty();
    }

    @Test void ambientTransactionCannotReachAnyDatabaseReadReservationCredentialOrHttp() {
        var reads = new AtomicInteger(); requests.onFind = reads::incrementAndGet;
        var outer = new TransactionTemplate(new MemoryTransactions());
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> execute(core(validResources()))))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(reads).hasValue(0); assertThat(accounting.reserves.get()).isZero();
        assertThat(credentialReads.get()).isZero(); assertThat(provider.sends.get()).isZero();
        assertThat(requests.value).isSameAs(original); assertThat(audit).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void transactionIntroducedAfterPreflightCannotSurroundTheFinalHttpCallback() {
        var core = coreWithCredentials((version, preflight, operation) -> {
            preflight.run();
            return new TransactionTemplate(new MemoryTransactions()).execute(status -> operation.apply("synthetic-placeholder-not-a-key"));
        });
        execute(core);
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.settled.get()).isZero();
        assertThat(accounting.unknown.get()).isOne();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void initialConnectionWaitThatExhaustsOriginalBudgetCannotEnterTheReadAction() {
        var readActions = new AtomicInteger(); requests.onFind = readActions::incrementAndGet;
        database = new LiveDatabaseWork() {
            @Override public <T> T transaction(LongSupplier remaining, boolean readOnly, Supplier<T> action) {
                nanos.set(Duration.ofSeconds(30).toNanos());
                requireBudget(remaining);
                return action.get();
            }
        };
        assertThatThrownBy(() -> execute(core(validResources())))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(readActions).hasValue(0); assertThat(accounting.reserves.get()).isZero();
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.unknown.get()).isZero();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING"); assertThat(audit).isEmpty();
    }

    @Test void initialUntrustedDatabaseFailureCannotExposeItsMessageOrCause() {
        database = new LiveDatabaseWork() {
            @Override public <T> T transaction(LongSupplier remaining, boolean readOnly, Supplier<T> action) {
                throw new IllegalStateException("private connection detail", new IllegalArgumentException("private JDBC detail"));
            }
        };
        assertThatThrownBy(() -> execute(core(validResources()))).isInstanceOf(AgentUnavailableException.class)
            .hasMessage("Agent storage is unavailable").hasNoCause();
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.reserves.get()).isZero();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING"); assertThat(audit).isEmpty();
    }

    @Test void databaseReadsLedgerCredentialsAndResultShareOneBudgetWithoutATransactionAcrossHttp() {
        var originalDatabase = database;
        var observedBudget = new AtomicReference<LongSupplier>();
        var databaseDepth = new AtomicInteger(); var reads = new AtomicInteger(); var writes = new AtomicInteger();
        database = new LiveDatabaseWork() {
            @Override public <T> T transaction(LongSupplier remaining, boolean readOnly, Supplier<T> action) {
                observedBudget.compareAndSet(null, remaining);
                assertThat(remaining).isSameAs(observedBudget.get());
                if (readOnly) reads.incrementAndGet(); else writes.incrementAndGet();
                databaseDepth.incrementAndGet();
                try { return originalDatabase.transaction(remaining, readOnly, action); }
                finally { databaseDepth.decrementAndGet(); }
            }
            @Override public <T> T cleanup(Supplier<T> action) { return originalDatabase.cleanup(action); }
        };
        requests.onFind = () -> assertThat(databaseDepth.get()).isPositive();
        accounting.onReserve = () -> assertThat(databaseDepth.get()).isZero();
        accounting.onCheck = () -> assertThat(databaseDepth.get()).isZero();
        provider.onSend = () -> {
            assertThat(databaseDepth.get()).isZero();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        };
        var core = new LiveExecutionCoordinator(requests, accounting, snapshot -> {
            assertThat(databaseDepth.get()).isPositive(); resourceChecks.incrementAndGet();
        }, provider, boundedAction((version, remaining, preflight, operation) -> {
            assertThat(remaining).isSameAs(observedBudget.get()); preflight.run();
            assertThat(databaseDepth.get()).isZero();
            return operation.apply("synthetic-placeholder-not-a-key");
        }), writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()),
            body -> {}, nanos::get, database);
        execute(core);
        assertThat(accounting.deadlines).hasSize(3).allSatisfy(remaining -> assertThat(remaining).isSameAs(observedBudget.get()));
        assertThat(reads).hasValue(7); assertThat(writes).hasValue(1); assertThat(databaseDepth).hasValue(0);
        assertThat(resourceChecks.get()).isEqualTo(4); assertThat(provider.sends.get()).isOne();
        assertThat(accounting.settled.get()).isOne(); assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED");
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

    @Test void legacyCredentialConsumerWithoutBoundedSupportCannotReadKeyOrSend() {
        var core = new LiveExecutionCoordinator(requests, accounting, validResources(), provider,
            (version, preflight, operation) -> {
                preflight.run();
                credentialReads.incrementAndGet();
                return operation.apply("synthetic-placeholder-not-a-key");
            }, writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()),
            body -> {}, nanos::get, database);
        execute(core);
        assertThat(credentialReads.get()).isZero();
        assertThat(provider.sends.get()).isZero();
        assertThat(accounting.settled.get()).isZero();
        assertThat(accounting.unknown.get()).isOne();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test void legacyLedgerCannotReserveOrSendAndCannotTerminalizeUnownedRunningRequest() {
        LiveAccountingOperations legacy = new LiveAccountingOperations() {
            public Admission reserve(UUID p, UUID b, UUID t, UUID r) { return accounting.reserve(p, b, t, r); }
            public SendApproval checkBeforeSend(UUID p, UUID b, UUID t, UUID r) { return accounting.checkBeforeSend(p, b, t, r); }
            public Outcome settle(UUID p, UUID r, String price, LiveTokenUsage usage) { return accounting.settle(p, r, price, usage); }
            public Outcome unknown(UUID p, UUID r) { return accounting.unknown(p, r); }
            public String preview(UUID p, List<UUID> templates) { throw new AssertionError(); }
            public LiveBatchView approve(UUID p, String o, boolean s, LiveBatchInput i) { throw new AssertionError(); }
            public LiveBatchView get(UUID p, UUID b) { throw new AssertionError(); }
            public LiveBatchView revoke(UUID p, UUID b, String o, boolean s) { throw new AssertionError(); }
            public String requestDigest(UUID p, UUID b, UUID t) { throw new AssertionError(); }
        };
        var core = new LiveExecutionCoordinator(requests, legacy, validResources(), provider,
            boundedFake((version, preflight, operation) -> {
                preflight.run(); credentialReads.incrementAndGet(); return operation.apply("synthetic-placeholder-not-a-key");
            }), writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()),
            body -> {}, nanos::get, database);
        Throwable failure = catchThrowable(() -> execute(core));
        assertThat(accounting.reserves.get()).isZero();
        assertThat(failure).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(accounting.checks.get()).isZero(); assertThat(provider.sends.get()).isZero();
        assertThat(credentialReads.get()).isZero(); assertThat(accounting.unknown.get()).isZero();
        assertThat(accounting.settled.get()).isZero(); assertThat(audit).isEmpty();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(requests.value.view().failure()).isNull();
    }

    @Test void defaultBoundedMethodRejectsEvenAConstantPositiveBudgetWithoutLegacyFallback() {
        LiveExecutionCoordinator.CredentialUse legacy = (version, preflight, operation) -> {
            credentialReads.incrementAndGet(); return operation.apply("synthetic-placeholder-not-a-key");
        };
        assertThatThrownBy(() -> legacy.use(accounting.credential, () -> Duration.ofSeconds(30).toNanos(),
                () -> {}, key -> { provider.sends.incrementAndGet(); return provider.reply; }))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_UNAVAILABLE").hasNoCause();
        assertThat(credentialReads.get()).isZero(); assertThat(provider.sends.get()).isZero();
    }

    @Test void originalClockBudgetFlowsThroughPreflightCredentialWaitAndHttpWithoutReset() {
        accounting.onReserve = () -> nanos.set(Duration.ofSeconds(5).toNanos());
        accounting.onCheck = () -> {
            if (accounting.checks.get() == 1) nanos.set(Duration.ofSeconds(8).toNanos());
        };
        provider.onSend = () -> nanos.set(Duration.ofSeconds(13).toNanos());
        execute(coreWithBoundedCredentials(boundedAction((version, remaining, preflight, operation) -> {
            assertThat(remaining.getAsLong()).isEqualTo(Duration.ofSeconds(25).toNanos());
            preflight.run();
            assertThat(remaining.getAsLong()).isEqualTo(Duration.ofSeconds(22).toNanos());
            nanos.set(Duration.ofSeconds(12).toNanos());
            assertThat(remaining.getAsLong()).isEqualTo(Duration.ofSeconds(18).toNanos());
            return operation.apply("synthetic-placeholder-not-a-key");
        })));
        assertThat(provider.timeout).isEqualTo(Duration.ofSeconds(18));
        assertThat(provider.sends.get()).isOne(); assertThat(accounting.settled.get()).isOne();
        assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED");
        assertThat(requests.value.view().usage().latencyMs()).isEqualTo(13_000);
        assertThat(requests.value.view().usage().providerLatencyMs()).isEqualTo(1_000);
    }

    @Test void interruptedBoundedCredentialScopeReportsZeroRemainingAndNeverSends() {
        try {
            execute(coreWithBoundedCredentials(boundedAction((version, remaining, preflight, operation) -> {
                preflight.run(); Thread.currentThread().interrupt();
                assertThat(remaining.getAsLong()).isZero();
                return operation.apply("synthetic-placeholder-not-a-key");
            })));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.settled.get()).isZero();
        assertThat(accounting.unknown.get()).isOne();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_TIMEOUT");
    }

    @Test void boundedConsumerTimeoutAfterActualReplyCannotEraseKnownCost() {
        execute(coreWithBoundedCredentials(boundedAction((version, remaining, preflight, operation) -> {
            preflight.run(); operation.apply("synthetic-placeholder-not-a-key");
            nanos.set(Duration.ofSeconds(31).toNanos());
            assertThat(remaining.getAsLong()).isZero();
            throw new IllegalStateException("private late credential cleanup detail");
        })));
        assertThat(provider.sends.get()).isOne(); assertThat(accounting.settled.get()).isOne();
        assertThat(accounting.unknown.get()).isZero();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(requests.value.view().result()).isNull();
        assertThat(requests.value.view().usage().actualCostMicrousd()).isEqualTo(273);
        assertThat(requests.value.view().usage().providerResponseId()).isEqualTo("resp-synthetic");
        assertThat(audit.toString()).doesNotContain("private late credential cleanup detail");
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
        var resultManager = new RollbackResultTransactions(requests);
        var failingWriter = new LiveResultTransitions(requests, event -> {
            throw new IllegalStateException("private audit detail");
        }, Clock.fixed(NOW, ZoneOffset.UTC), resultManager, memoryDatabaseWork(resultManager));
        var core = new LiveExecutionCoordinator(requests, accounting, validResources(), provider,
            boundedFake((version, preflight, operation) -> {
                preflight.run(); return operation.apply("synthetic-placeholder-not-a-key");
            }), failingWriter,
            new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()), body -> {}, nanos::get, database);
        assertThatThrownBy(() -> execute(core)).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(accounting.settled.get()).isOne(); assertThat(accounting.unknown.get()).isZero();
        assertThat(provider.sends.get()).isOne();
        assertThat(requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(requests.value.view().result()).isNull(); assertThat(audit).isEmpty();
    }

    @Test void withdrawalAfterReservationAndDuringFreshPreflightPreventsHttp() {
        accounting.rejectCheck = 2;
        execute(core(validResources()));
        assertThat(accounting.unknown.get()).isOne(); assertThat(accounting.settled.get()).isZero(); assertThat(provider.sends.get()).isZero();
    }

    @Test void freshPreflightNeverEntersAnotherModuleWhileCredentialCallbackHoldsStoreLock() {
        var credentialLockHeld = new AtomicBoolean();
        accounting.onCheck = () -> assertThat(credentialLockHeld.get())
            .as("Accounting/settings checks must precede the credential store lock").isFalse();
        requests.onFind = () -> assertThat(credentialLockHeld.get())
            .as("RUNNING state reads must precede the credential store lock").isFalse();
        provider.onSend = () -> assertThat(credentialLockHeld.get())
            .as("Only the final send callback holds the credential store lock").isTrue();
        var core = new LiveExecutionCoordinator(requests, accounting, snapshot -> {
            assertThat(credentialLockHeld.get()).as("Resource authorization must precede the credential store lock").isFalse();
            resourceChecks.incrementAndGet();
        }, provider, boundedFake((version, preflight, operation) -> {
            preflight.run();
            assertThat(version).isEqualTo(accounting.credential);
            credentialReads.incrementAndGet();
            credentialLockHeld.set(true);
            try { return operation.apply("synthetic-placeholder-not-a-key"); }
            finally { credentialLockHeld.set(false); }
        }), writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()), body -> {
            assertThat(credentialLockHeld.get()).as("Token/network policy must precede the credential store lock").isFalse();
        }, nanos::get, database);

        execute(core);

        assertThat(requests.value.view().state()).isEqualTo("SUCCEEDED");
        assertThat(accounting.checks.get()).isEqualTo(2);
        assertThat(resourceChecks.get()).isEqualTo(4);
        assertThat(credentialReads.get()).isOne();
        assertThat(provider.sends.get()).isOne();
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
        var core = new LiveExecutionCoordinator(requests, accounting, validResources(), provider, (version, preflight, operation) -> {
            throw new AssertionError();
        }, writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(UUID.randomUUID(), Set.of()), body -> {}, nanos::get, database);
        assertThatThrownBy(() -> execute(core)).isInstanceOf(ProjectPermissionDeniedException.class);
        assertThat(requests.value.view().state()).isEqualTo("RUNNING"); assertThat(accounting.reserves.get()).isZero();
    }

    @Test void credentialConsumerCannotSendTwice() {
        var core = new LiveExecutionCoordinator(requests, accounting, validResources(), provider, boundedFake((version, preflight, operation) -> {
            preflight.run();
            operation.apply("synthetic-placeholder-not-a-key"); return operation.apply("synthetic-placeholder-not-a-key");
        }), writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()), body -> {}, nanos::get, database);
        execute(core); assertKnownReplyPreservedWithoutOutput();
    }

    @Test void skippedPreflightCannotReachHttp() {
        execute(coreWithCredentials((version, preflight, operation) -> operation.apply("synthetic-placeholder-not-a-key")));
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.settled.get()).isZero();
        assertThat(accounting.unknown.get()).isOne(); assertThat(accounting.checks.get()).isOne();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test void swallowedPreflightFailureStillCannotReachHttp() {
        accounting.rejectCheck = 2;
        execute(coreWithCredentials((version, preflight, operation) -> {
            assertThatThrownBy(preflight::run).isInstanceOf(AgentEnablementInvalidStateException.class);
            return operation.apply("synthetic-placeholder-not-a-key");
        }));
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.settled.get()).isZero();
        assertThat(accounting.unknown.get()).isOne(); assertThat(accounting.checks.get()).isEqualTo(2);
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test void repeatedPreflightCannotBeSwallowedToPermitHttp() {
        execute(coreWithCredentials((version, preflight, operation) -> {
            preflight.run();
            assertThatThrownBy(preflight::run).isInstanceOf(LiveResponsePort.CallFailure.class);
            return operation.apply("synthetic-placeholder-not-a-key");
        }));
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.settled.get()).isZero();
        assertThat(accounting.unknown.get()).isOne(); assertThat(accounting.checks.get()).isEqualTo(2);
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test void consumerCannotReturnBillableReplyWithoutRunningFinalSend() {
        execute(coreWithCredentials((version, preflight, operation) -> {
            preflight.run(); return provider.reply;
        }));
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.settled.get()).isZero();
        assertThat(accounting.unknown.get()).isOne(); assertThat(requests.value.view().usage()).isNull();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test void consumerFailureAfterFinalSendPreservesKnownUsageAndFailsOutput() {
        execute(coreWithCredentials((version, preflight, operation) -> {
            preflight.run(); operation.apply("synthetic-placeholder-not-a-key");
            throw new IllegalStateException("private credential cleanup detail");
        }));
        assertKnownReplyPreservedWithoutOutput();
    }

    @Test void repeatedPreflightAfterFinalSendPreservesKnownUsageAndFailsOutput() {
        execute(coreWithCredentials((version, preflight, operation) -> {
            preflight.run(); var reply = operation.apply("synthetic-placeholder-not-a-key");
            assertThatThrownBy(preflight::run).isInstanceOf(LiveResponsePort.CallFailure.class);
            return reply;
        }));
        assertKnownReplyPreservedWithoutOutput();
    }

    @Test void alteredConsumerReturnCannotReplaceActualSentReplyUsageOrMetadata() {
        execute(coreWithCredentials((version, preflight, operation) -> {
            preflight.run(); operation.apply("synthetic-placeholder-not-a-key");
            return new LiveResponsePort.Reply(INSUFFICIENT, null,
                new LiveTokenUsage(1, 1, 0, 2, 0), "resp-altered", "deepseek-flash");
        }));
        assertKnownReplyPreservedWithoutOutput();
    }

    @Test void cachedFinalCallbackCannotSendAfterConsumerReturnedAndExecutionFailed() {
        var cached = new AtomicReference<java.util.function.Function<String, LiveResponsePort.Reply>>();
        execute(coreWithCredentials((version, preflight, operation) -> {
            preflight.run(); cached.set(operation); return null;
        }));
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
        assertThatThrownBy(() -> cached.get().apply("synthetic-placeholder-not-a-key"))
            .isInstanceOf(LiveResponsePort.CallFailure.class);
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.unknown.get()).isOne();
        assertThat(accounting.settled.get()).isZero();
    }

    @Test void anotherThreadCannotRunFinalCallbackEvenWhileConsumerIsActive() {
        execute(coreWithCredentials((version, preflight, operation) -> {
            preflight.run();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var failure = catchThrowable(() -> executor.submit(() -> operation.apply("synthetic-placeholder-not-a-key"))
                    .get(5, TimeUnit.SECONDS));
                assertThat(failure).isInstanceOf(ExecutionException.class).cause().isInstanceOf(LiveResponsePort.CallFailure.class);
            }
            return null;
        }));
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.unknown.get()).isOne();
        assertThat(accounting.settled.get()).isZero();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
    }

    @Test void anotherThreadCannotRunPreflightEvenWhileConsumerIsActive() {
        execute(coreWithCredentials((version, preflight, operation) -> {
            try (var executor = Executors.newSingleThreadExecutor()) {
                var failure = catchThrowable(() -> executor.submit(preflight).get(5, TimeUnit.SECONDS));
                assertThat(failure).isInstanceOf(ExecutionException.class).cause().isInstanceOf(LiveResponsePort.CallFailure.class);
            }
            return null;
        }));
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.unknown.get()).isOne();
        assertThat(accounting.checks.get()).isOne(); assertThat(accounting.settled.get()).isZero();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
    }

    private void assertKnownReplyPreservedWithoutOutput() {
        assertThat(provider.sends.get()).isOne(); assertThat(accounting.settled.get()).isOne();
        assertThat(accounting.unknown.get()).isZero();
        assertThat(requests.value.view().failure()).isNotNull();
        assertThat(requests.value.view().failure().code()).isEqualTo("OUTPUT_INVALID");
        assertThat(requests.value.view().result()).isNull();
        assertThat(requests.value.view().usage().inputTokens()).isEqualTo(1_000);
        assertThat(requests.value.view().usage().outputTokens()).isEqualTo(100);
        assertThat(requests.value.view().usage().actualCostMicrousd()).isEqualTo(273);
        assertThat(requests.value.view().usage().providerResponseId()).isEqualTo("resp-synthetic");
        assertThat(requests.value.view().usage().actualModelId()).isEqualTo("deepseek-flash");
    }

    @Test void credentialLockWaitExhaustsDeadlineAfterFreshPreflightAndBeforeHttp() {
        execute(coreWithCredentials((version, preflight, operation) -> {
            preflight.run();
            // Simulates time spent waiting for the slot monitor, without resetting the execution deadline.
            nanos.set(Duration.ofSeconds(30).toNanos());
            credentialReads.incrementAndGet();
            return operation.apply("synthetic-placeholder-not-a-key");
        }));
        assertThat(provider.sends.get()).isZero(); assertThat(accounting.settled.get()).isZero();
        assertThat(accounting.unknown.get()).isOne(); assertThat(accounting.checks.get()).isEqualTo(2);
        assertThat(credentialReads.get()).isOne();
        assertThat(requests.value.view().failure().code()).isEqualTo("MODEL_TIMEOUT");
    }

    private LiveExecutionCoordinator coreWithCredentials(LiveExecutionCoordinator.CredentialUse credentials) {
        return coreWithBoundedCredentials(boundedFake(credentials));
    }

    private LiveExecutionCoordinator coreWithBoundedCredentials(LiveExecutionCoordinator.CredentialUse credentials) {
        return new LiveExecutionCoordinator(requests, accounting, validResources(), provider, credentials,
            writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()), body -> {}, nanos::get, database);
    }

    /** Fake callbacks have no real lock; opt them into the bounded contract explicitly, never in production. */
    private static LiveExecutionCoordinator.CredentialUse boundedFake(LiveExecutionCoordinator.CredentialUse delegate) {
        return new LiveExecutionCoordinator.CredentialUse() {
            @Override public LiveResponsePort.Reply use(UUID version, Runnable preflight,
                    java.util.function.Function<String, LiveResponsePort.Reply> operation) {
                throw new AssertionError("Core must not select the legacy unbounded entry point");
            }
            @Override public LiveResponsePort.Reply use(UUID version, java.util.function.LongSupplier remaining,
                    Runnable preflight, java.util.function.Function<String, LiveResponsePort.Reply> operation) {
                if (remaining.getAsLong() <= 0) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
                return delegate.use(version, preflight, operation);
            }
        };
    }

    private static LiveExecutionCoordinator.CredentialUse boundedAction(BoundedCredentialAction action) {
        return new LiveExecutionCoordinator.CredentialUse() {
            @Override public LiveResponsePort.Reply use(UUID version, Runnable preflight,
                    java.util.function.Function<String, LiveResponsePort.Reply> operation) {
                throw new AssertionError("The bounded credential entry point is required");
            }
            @Override public LiveResponsePort.Reply use(UUID version, java.util.function.LongSupplier remaining,
                    Runnable preflight, java.util.function.Function<String, LiveResponsePort.Reply> operation) {
                return action.use(version, remaining, preflight, operation);
            }
        };
    }

    @FunctionalInterface private interface BoundedCredentialAction {
        LiveResponsePort.Reply use(UUID version, java.util.function.LongSupplier remaining, Runnable preflight,
            java.util.function.Function<String, LiveResponsePort.Reply> operation);
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
            (version, preflight, operation) -> { throw new AssertionError("Replay must not consume credentials"); },
            writer(), new AgentOutputValidator(new ObjectMapper()), () -> new CurrentActor(owner, Set.of()),
            replayPolicy, nanos::get, database);
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
        final List<LongSupplier> deadlines = new CopyOnWriteArrayList<>();
        Runnable onReserve = () -> {}, onSettle = () -> {}, onCheck = () -> {}; int rejectCheck;
        public Admission reserve(UUID p, UUID b, UUID t, UUID r) { reserves.incrementAndGet(); onReserve.run(); return new Admission(r, 4200, owns.compareAndSet(false, true)); }
        public Admission reserve(UUID p, UUID b, UUID t, UUID r, LongSupplier remaining) {
            deadlines.add(remaining); requireBudget(remaining); return reserve(p, b, t, r);
        }
        public SendApproval checkBeforeSend(UUID p, UUID b, UUID t, UUID r) {
            onCheck.run();
            if (checks.incrementAndGet() == rejectCheck) throw new AgentEnablementInvalidStateException();
            return new SendApproval(credential, Set.of("deepseek-flash"));
        }
        public SendApproval checkBeforeSend(UUID p, UUID b, UUID t, UUID r, LongSupplier remaining) {
            deadlines.add(remaining); requireBudget(remaining); return checkBeforeSend(p, b, t, r);
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
        Runnable onFind = () -> {};
        MemoryRequests(AgentSnapshot value) { this.value = value; }
        public Optional<AgentSnapshot> find(UUID p, UUID r) { onFind.run(); return value.view().projectId().equals(p) && value.view().id().equals(r) ? Optional.of(value) : Optional.empty(); }
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

    /** Explicit fake persistence: models transaction/commit boundaries, not a production unbounded fallback. */
    static LiveDatabaseWork memoryDatabaseWork(PlatformTransactionManager manager) {
        return new LiveDatabaseWork() {
            @Override public <T> T transaction(LongSupplier remaining, boolean readOnly, Supplier<T> action) {
                requireBudget(remaining);
                var transaction = new TransactionTemplate(manager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                transaction.setReadOnly(readOnly);
                return transaction.execute(status -> {
                    requireBudget(remaining);
                    T value = action.get();
                    requireBudget(remaining);
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void beforeCommit(boolean ignored) { requireBudget(remaining); }
                    });
                    return value;
                });
            }
            @Override public <T> T cleanup(Supplier<T> action) {
                var transaction = new TransactionTemplate(manager);
                transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return transaction.execute(status -> action.get());
            }
        };
    }

    private static void requireBudget(LongSupplier remaining) {
        if (Thread.currentThread().isInterrupted() || remaining.getAsLong() <= 0) {
            throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
        }
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
