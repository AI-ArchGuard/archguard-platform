package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.common.TraceTaskScope;
import io.github.aiarchguard.platform.identity.AuthenticatedTaskScope;
import io.github.aiarchguard.platform.identity.CurrentActor;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.identity.InactiveAuthenticatedTaskException;
import io.github.aiarchguard.platform.project.ProjectAccessView;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.project.ProjectMemberRole;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class LiveRequestDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String PRIVATE_DETAIL = "private-synthetic-detail-not-a-secret";
    private final Logger logger = (Logger) LoggerFactory.getLogger(LiveRequestDispatcher.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Fixture fixture;

    @BeforeEach void setup() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        fixture = new Fixture(); logs.start(); logger.addAppender(logs);
    }

    @AfterEach void cleanup() {
        logger.detachAppender(logs); logs.stop(); MDC.clear(); TransactionSynchronizationManager.clear();
    }

    @Test void publicConstructionDeniesBeforeLookupCaptureQueueOrCore() {
        var unusedCore = new LiveExecutionCoordinator(fixture.requests, null,
            snapshot -> { throw new AssertionError("Default dispatch must not execute resources"); }, null,
            (version, preflight, operation) -> { throw new AssertionError("Default dispatch must not consume credentials"); },
            null, null, fixture.actors, body -> { throw new AssertionError("Default dispatch must not prepare egress"); }, () -> 0L,
            new LiveDatabaseWork() {});
        var dispatcher = new LiveRequestDispatcher(fixture.requests, fixture.links, fixture.actors, fixture.scope,
            new TraceTaskScope(), fixture.transitions, unusedCore, fixture.queue);
        assertUnavailable(() -> dispatcher.dispatch(fixture.project, fixture.request));
        assertThat(fixture.requests.lookups).isZero(); assertThat(fixture.links.lookups).isZero();
        assertThat(fixture.scope.captures).isZero(); assertNoWork();
    }

    @Test void independentQueueUsesPersistedIdentityTraceAndCanonicalClaimArguments() throws Exception {
        fixture.dispatcher().dispatch(fixture.project, fixture.request);
        assertThat(fixture.scope.capturedRequester).isEqualTo(fixture.owner);
        assertThat(fixture.queue.submissions).isOne(); assertThat(fixture.executions).isEmpty();
        assertThat(fixture.links.claims).isZero(); assertThat(fixture.audit).isEmpty();
        var unrelatedActor = new CurrentActor(UUID.randomUUID(), Set.of("unrelated"));
        fixture.currentActor.set(unrelatedActor);
        MDC.setContextMap(Map.of("caller", "must-not-be-forwarded"));
        var task = fixture.queue.take();
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                MDC.setContextMap(Map.of("worker", "restore-me", "traceId", "old-worker-trace"));
                task.run();
                assertThat(fixture.currentActor.get()).isEqualTo(unrelatedActor);
                assertThat(fixture.scope.active.get()).isFalse();
                assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("worker", "restore-me", "traceId", "old-worker-trace"));
                MDC.clear();
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("caller", "must-not-be-forwarded"));
        assertThat(fixture.executions).containsExactly(List.of(fixture.project, fixture.batch, fixture.template, fixture.request));
        assertThat(fixture.requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(fixture.links.claims).isOne(); assertThat(fixture.transactions.commits).isOne();
        assertThat(fixture.requests.finishes).isZero();
        assertThat(fixture.audit).hasSize(1);
        assertThat(fixture.audit.getFirst().action()).isEqualTo("agent.live.request.running");
        assertThat(fixture.audit.getFirst().traceId()).isEqualTo(TRACE);
        assertThat(fixture.audit.getFirst().actorId()).isEqualTo(fixture.owner);
    }

    @Test void duplicateQueuedCopiesOnlyExecuteTheCommittedClaimWinner() {
        var dispatcher = fixture.dispatcher();
        dispatcher.dispatch(fixture.project, fixture.request); dispatcher.dispatch(fixture.project, fixture.request);
        assertThat(fixture.queue.submissions).isEqualTo(2);
        fixture.queue.runNext(); fixture.queue.runNext();
        assertThat(fixture.executions).hasSize(1); assertThat(fixture.links.claims).isOne();
        assertThat(fixture.requests.finishes).isZero(); assertThat(fixture.audit).hasSize(1);
        assertThat(fixture.requests.value.view().state()).isEqualTo("RUNNING");
    }

    @Test void losingClaimDoesNotExecuteOrFailSharedWork() {
        fixture.links.wins = false;
        fixture.dispatcher().dispatch(fixture.project, fixture.request); fixture.queue.runNext();
        assertThat(fixture.links.claims).isOne(); assertThat(fixture.executions).isEmpty();
        assertThat(fixture.requests.finishes).isZero(); assertThat(fixture.audit).isEmpty();
        assertThat(fixture.requests.value.view().state()).isEqualTo("QUEUED");
    }

    @Test void wrongRequesterIsRejectedBeforeAssociationCaptureOrQueue() {
        fixture.currentActor.set(new CurrentActor(UUID.randomUUID(), Set.of()));
        assertThatThrownBy(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request))
            .isInstanceOf(ProjectPermissionDeniedException.class).hasNoCause();
        assertThat(fixture.links.lookups).isZero(); assertThat(fixture.scope.captures).isZero(); assertNoWork();
    }

    @Test void missingRequestOrAssociationCannotQueue() {
        fixture.requests.present = false;
        assertThatThrownBy(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request))
            .isInstanceOf(AgentNotFoundException.class).hasNoCause();
        fixture.requests.present = true; fixture.links.present = false;
        assertThatThrownBy(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request))
            .isInstanceOf(AgentNotFoundException.class).hasNoCause();
        assertThat(fixture.scope.captures).isZero(); assertNoWork();
    }

    @Test void runningAndTerminalRequestsReturnWithoutQueueingOrAssociationLookup() {
        for (String state : List.of("RUNNING", "FAILED", "SUCCEEDED")) {
            fixture.requests.state(state, null, NOW);
            assertThatCode(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request)).doesNotThrowAnyException();
        }
        assertThat(fixture.links.lookups).isZero(); assertThat(fixture.scope.captures).isZero(); assertNoWork();
    }

    @Test void actualTransactionQueuesOnlyAfterCommitNotDuringPreparation() {
        var outer = new MemoryTransactions(fixture.requests, fixture.audit);
        new TransactionTemplate(outer).executeWithoutResult(status -> {
            fixture.dispatcher().dispatch(fixture.project, fixture.request);
            assertNoWork();
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        });
        assertThat(outer.commits).isOne(); assertThat(outer.rollbacks).isZero();
        assertThat(fixture.queue.submissions).isOne(); assertThat(fixture.executions).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        fixture.queue.runNext(); assertThat(fixture.executions).hasSize(1);
    }

    @Test void rollbackNeverQueuesClaimsExecutesOrFailsTheRequest() {
        var outer = new MemoryTransactions(fixture.requests, fixture.audit);
        new TransactionTemplate(outer).executeWithoutResult(status -> {
            fixture.dispatcher().dispatch(fixture.project, fixture.request); assertNoWork(); status.setRollbackOnly();
        });
        assertThat(outer.commits).isZero(); assertThat(outer.rollbacks).isOne(); assertNoWork();
        assertThat(fixture.requests.value.view().state()).isEqualTo("QUEUED");
    }

    @Test void failedClaimCommitCannotExecuteFailOrRetrySharedWork() {
        fixture.transactions.commitFailure = privateFailure();
        fixture.dispatcher().dispatch(fixture.project, fixture.request);
        assertThatCode(fixture.queue::runNext).doesNotThrowAnyException();
        assertThat(fixture.links.claims).isOne(); assertThat(fixture.executions).isEmpty();
        assertThat(fixture.requests.finishes).isZero(); assertThat(fixture.queue.submissions).isOne();
        assertSafeLogCode("INTERNAL_ERROR");
    }

    @Test void inconsistentTransactionSynchronizationStatesFailClosedWithoutQueue() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertUnavailable(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request)); }
        finally { TransactionSynchronizationManager.clear(); }
        TransactionSynchronizationManager.initSynchronization();
        try { assertUnavailable(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request)); }
        finally { TransactionSynchronizationManager.clear(); }
        assertNoWork();
    }

    @Test void executorRejectionIsSanitizedWithoutFailingOrRetryingSharedWork() {
        fixture.queue.failure = new RejectedExecutionException(PRIVATE_DETAIL, privateFailure());
        assertUnavailable(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request));
        assertThat(fixture.queue.submissions).isOne(); assertThat(fixture.queue.tasks).isEmpty();
        assertThat(fixture.executions).isEmpty(); assertThat(fixture.links.claims).isZero();
        assertThat(fixture.requests.finishes).isZero(); assertThat(fixture.audit).isEmpty();
        assertThat(fixture.requests.value.view().state()).isEqualTo("QUEUED");
    }

    @Test void afterCommitQueueRejectionCannotRollbackCommitOrFailAnotherExecutor() {
        fixture.queue.failure = new RejectedExecutionException(PRIVATE_DETAIL, privateFailure());
        var outer = new MemoryTransactions(fixture.requests, fixture.audit);
        assertUnavailable(() -> new TransactionTemplate(outer).executeWithoutResult(status ->
            fixture.dispatcher().dispatch(fixture.project, fixture.request)));
        assertThat(outer.commits).isOne(); assertThat(outer.rollbacks).isZero();
        assertThat(fixture.queue.submissions).isOne(); assertThat(fixture.links.claims).isZero();
        assertThat(fixture.requests.finishes).isZero(); assertThat(fixture.audit).isEmpty();
    }

    @Test void exceptionBeforeClaimCannotFailTheRequestOrPropagatePrivateDetails() {
        fixture.dispatcher().dispatch(fixture.project, fixture.request);
        fixture.links.claimFailure = privateFailure();
        assertThatCode(fixture.queue::runNext).doesNotThrowAnyException();
        assertThat(fixture.links.claims).isOne(); assertThat(fixture.executions).isEmpty();
        assertThat(fixture.requests.finishes).isZero(); assertThat(fixture.audit).isEmpty();
        assertThat(fixture.requests.value.view().state()).isEqualTo("QUEUED");
        assertSafeLogCode("INTERNAL_ERROR");
    }

    @Test void coreFailureOnlyTerminalizesTheWinnerAndNeverRetries() {
        fixture.executionFailure = privateFailure();
        var dispatcher = fixture.dispatcher();
        dispatcher.dispatch(fixture.project, fixture.request); dispatcher.dispatch(fixture.project, fixture.request);
        assertThatCode(fixture.queue::runNext).doesNotThrowAnyException();
        var failed = fixture.requests.value;
        assertThat(failed.view().state()).isEqualTo("FAILED");
        assertThat(failed.view().failure().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.view().failure().message()).doesNotContain(PRIVATE_DETAIL);
        fixture.queue.runNext(); dispatcher.dispatch(fixture.project, fixture.request);
        assertThat(fixture.requests.value).isSameAs(failed);
        assertThat(fixture.executions).hasSize(1); assertThat(fixture.links.claims).isOne();
        assertThat(fixture.requests.finishes).isOne(); assertThat(fixture.queue.submissions).isEqualTo(2);
        assertThat(fixture.audit).extracting(AuditEvent::action)
            .containsExactly("agent.live.request.running", "agent.live.result_failed");
        assertThat(fixture.audit.toString()).doesNotContain(PRIVATE_DETAIL, "private cause");
        assertSafeLogCode("INTERNAL_ERROR");
    }

    @Test void capturedScopeEntryRejectionNeverStartsOrFailsTheRequest() {
        fixture.scope.entryFailure = new InactiveAuthenticatedTaskException();
        fixture.dispatcher().dispatch(fixture.project, fixture.request);
        assertThatCode(fixture.queue::runNext).doesNotThrowAnyException();
        assertThat(fixture.requests.lookups).isOne(); assertThat(fixture.links.claims).isZero();
        assertThat(fixture.scope.checks).isZero(); assertThat(fixture.executions).isEmpty();
        assertThat(fixture.requests.finishes).isZero(); assertThat(fixture.audit).isEmpty();
        assertSafeLogCode("AUTHORIZATION_REVOKED");
    }

    @Test void inactiveScopeBeforeStartCannotClaimOrFailSharedWork() {
        fixture.dispatcher().dispatch(fixture.project, fixture.request); fixture.scope.rejectChecks = true;
        fixture.queue.runNext();
        assertThat(fixture.scope.checks).isOne(); assertThat(fixture.links.claims).isZero();
        assertThat(fixture.executions).isEmpty(); assertThat(fixture.requests.finishes).isZero();
        assertThat(fixture.audit).isEmpty(); assertSafeLogCode("AUTHORIZATION_REVOKED");
    }

    @Test void scopeExpiryAfterClaimCommitPreventsCoreAndFailsOnlyThatWinner() {
        fixture.transactions.afterCommit = () -> fixture.scope.rejectChecks = true;
        fixture.dispatcher().dispatch(fixture.project, fixture.request); fixture.queue.runNext();
        assertThat(fixture.links.claims).isOne(); assertThat(fixture.executions).isEmpty();
        assertThat(fixture.requests.finishes).isOne();
        assertThat(fixture.requests.value.view().failure().code()).isEqualTo("AUTHORIZATION_REVOKED");
        assertThat(fixture.audit).extracting(AuditEvent::action)
            .containsExactly("agent.live.request.running", "agent.live.result_failed");
        assertSafeLogCode("AUTHORIZATION_REVOKED");
    }

    @Test void unexpectedPersistenceFailureIsSanitizedWithoutRetryOrOverwritingState() {
        fixture.executionFailure = privateFailure(); fixture.requests.finishFailure = privateFailure();
        fixture.dispatcher().dispatch(fixture.project, fixture.request);
        assertThatCode(fixture.queue::runNext).doesNotThrowAnyException();
        assertThat(fixture.executions).hasSize(1); assertThat(fixture.requests.finishes).isOne();
        assertThat(fixture.requests.value.view().state()).isEqualTo("RUNNING");
        assertThat(fixture.requests.value.view().failure()).isNull();
        assertThat(fixture.audit).hasSize(1); assertThat(fixture.queue.tasks).isEmpty();
        assertSafeLogCode("MODEL_UNAVAILABLE");
    }

    @Test void capturedRunnableCannotBeReusedToRetryExecution() {
        fixture.dispatcher().dispatch(fixture.project, fixture.request); var task = fixture.queue.take();
        task.run(); assertThatCode(task::run).doesNotThrowAnyException();
        assertThat(fixture.executions).hasSize(1); assertThat(fixture.links.claims).isOne();
        assertThat(fixture.requests.finishes).isZero(); assertThat(fixture.audit).hasSize(1);
    }

    // These guards reproduce unexpected pre-queue errors without passing their private messages/cause to a caller.
    @Test void unexpectedAdmissionFailureIsSanitizedBeforeAnyLookup() {
        assertUnavailable(() -> fixture.dispatcher(() -> { throw privateFailure(); }).dispatch(fixture.project, fixture.request));
        assertThat(fixture.requests.lookups).isZero(); assertThat(fixture.scope.captures).isZero(); assertNoWork();
    }

    @Test void unexpectedRequestLookupFailureIsSanitizedWithoutQueueOrFailureWrite() {
        fixture.requests.lookupFailure = privateFailure();
        assertUnavailable(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request));
        assertThat(fixture.links.lookups).isZero(); assertThat(fixture.scope.captures).isZero(); assertNoWork();
    }

    @Test void unexpectedAssociationLookupFailureIsSanitizedWithoutQueueOrFailureWrite() {
        fixture.links.lookupFailure = privateFailure();
        assertUnavailable(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request));
        assertThat(fixture.scope.captures).isZero(); assertNoWork();
    }

    @Test void unexpectedIdentityCaptureFailureIsSanitizedWithoutQueueOrFailureWrite() {
        fixture.scope.captureFailure = privateFailure();
        assertUnavailable(() -> fixture.dispatcher().dispatch(fixture.project, fixture.request));
        assertThat(fixture.scope.captures).isOne(); assertNoWork();
    }

    @Test void nullIdsRemainStableInputErrorsAndCannotLookupOrQueue() {
        assertThatThrownBy(() -> fixture.dispatcher().dispatch(null, fixture.request))
            .isInstanceOf(AgentInvalidException.class).hasNoCause();
        assertThatThrownBy(() -> fixture.dispatcher().dispatch(fixture.project, null))
            .isInstanceOf(AgentInvalidException.class).hasNoCause();
        assertThat(fixture.requests.lookups).isZero(); assertNoWork();
    }

    private void assertNoWork() {
        assertThat(fixture.queue.submissions).isZero(); assertThat(fixture.queue.tasks).isEmpty();
        assertThat(fixture.links.claims).isZero(); assertThat(fixture.executions).isEmpty();
        assertThat(fixture.requests.finishes).isZero(); assertThat(fixture.audit).isEmpty();
    }

    private void assertSafeLogCode(String code) {
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage)).anyMatch(message -> message.contains("code=" + code))
            .noneMatch(message -> message.contains(PRIVATE_DETAIL) || message.contains("private cause"));
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    private static void assertUnavailable(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(AgentUnavailableException.class)
            .hasMessage("Agent storage is unavailable").hasNoCause();
    }

    private static RuntimeException privateFailure() {
        return new IllegalStateException(PRIVATE_DETAIL, new IllegalArgumentException("private cause"));
    }

    private static final class Fixture {
        final UUID project = UUID.randomUUID(), request = UUID.randomUUID(), owner = UUID.randomUUID();
        final UUID batch = UUID.randomUUID(), template = UUID.randomUUID();
        final AtomicReference<CurrentActor> currentActor = new AtomicReference<>(new CurrentActor(owner, Set.of()));
        final CurrentActorProvider actors = currentActor::get;
        final List<AuditEvent> audit = new ArrayList<>();
        final List<List<UUID>> executions = new ArrayList<>();
        final MemoryRequests requests = new MemoryRequests(snapshot());
        final MemoryLinks links = new MemoryLinks(this);
        final FakeScope scope = new FakeScope(currentActor);
        final ManualQueue queue = new ManualQueue();
        final MemoryTransactions transactions = new MemoryTransactions(requests, audit);
        final LiveDispatchTransitions transitions = new LiveDispatchTransitions(requests, links, new ProjectAuthorization() {
            public ProjectAccessView requireViewer(UUID id) {
                assertThat(id).isEqualTo(project);
                return new ProjectAccessView(project, "synthetic-project", "Synthetic project", ProjectMemberRole.VIEWER);
            }
            public ProjectAccessView requireMaintainer(UUID id) { throw new AssertionError("Dispatch does not request Maintainer"); }
            public ProjectAccessView requireViewerForActor(UUID id, UUID actor) { throw new AssertionError("No self-reported worker actor"); }
        }, actors, scope, () -> MDC.get("traceId"), audit::add, Clock.fixed(NOW, ZoneOffset.UTC), transactions);
        RuntimeException executionFailure;

        LiveRequestDispatcher dispatcher() { return dispatcher(() -> {}); }
        LiveRequestDispatcher dispatcher(Runnable admission) {
            return new LiveRequestDispatcher(requests, links, actors, scope, new TraceTaskScope(), transitions,
                (p, b, t, r) -> {
                    assertThat(currentActor.get().id()).isEqualTo(owner);
                    assertThat(scope.active.get()).isTrue();
                    assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("traceId", TRACE));
                    assertThat(requests.value.view().state()).isEqualTo("RUNNING");
                    assertThat(transactions.commits).isPositive();
                    executions.add(List.of(p, b, t, r));
                    if (executionFailure != null) throw executionFailure;
                }, queue, admission);
        }
        AgentSnapshot snapshot() {
            var input = new AgentModelPort.ModelInput("FINDING_EXPLANATION", "0.1.0", LiveAccountingOperations.EXPLANATION_PROMPT,
                "synthetic-finding", "synthetic.rule", "0.1.0", "high", "Synthetic evidence", "synthetic.module", List.of(), List.of(), null);
            var bindings = new AgentRequestView.VersionBindings(UUID.randomUUID(), "a".repeat(64), null,
                List.of(UUID.randomUUID()), List.of(), input.promptVersion(), LiveAccountingOperations.MODEL_PROFILE,
                "responses-v1-restricted", "deepseek-flash", "0.1.0", LiveCostPolicy.VERSION, "b".repeat(64));
            return new AgentSnapshot(new AgentRequestView(request, project, owner, TRACE, input.purpose(), "QUEUED", bindings,
                null, null, null, NOW, NOW), input, List.of());
        }
    }

    private static final class ManualQueue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        int submissions;
        RuntimeException failure;
        public void execute(Runnable task) {
            submissions++; if (failure != null) throw failure; tasks.addLast(task);
        }
        Runnable take() { return tasks.removeFirst(); }
        void runNext() { take().run(); }
    }

    private static final class FakeScope implements AuthenticatedTaskScope {
        final AtomicReference<CurrentActor> current;
        final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
        int captures, checks;
        UUID capturedRequester;
        boolean rejectChecks;
        RuntimeException captureFailure, entryFailure;
        FakeScope(AtomicReference<CurrentActor> current) { this.current = current; }
        public Runnable capture(UUID expected, Runnable task) {
            captures++; capturedRequester = expected;
            if (captureFailure != null) throw captureFailure;
            CurrentActor captured = current.get(); assertThat(captured.id()).isEqualTo(expected);
            var used = new AtomicBoolean();
            return () -> {
                if (entryFailure != null) throw entryFailure;
                if (!used.compareAndSet(false, true)) throw new InactiveAuthenticatedTaskException();
                CurrentActor previous = current.getAndSet(captured); boolean wasActive = active.get(); active.set(true);
                try { task.run(); }
                finally { current.set(previous); active.set(wasActive); }
            };
        }
        public void requireActive() {
            checks++; if (!active.get() || rejectChecks) throw new InactiveAuthenticatedTaskException();
        }
    }

    private static final class MemoryLinks implements LiveRequestStore {
        final Fixture fixture;
        final Link link;
        int lookups, claims;
        boolean present = true, wins = true;
        RuntimeException lookupFailure, claimFailure;
        MemoryLinks(Fixture fixture) {
            this.fixture = fixture;
            link = new Link(fixture.request, fixture.project, fixture.batch, fixture.template, UUID.randomUUID(), UUID.randomUUID(), 1, NOW);
        }
        public Optional<Link> find(UUID project, UUID request) {
            lookups++; if (lookupFailure != null) throw lookupFailure;
            return present && link.projectId().equals(project) && link.requestId().equals(request) ? Optional.of(link) : Optional.empty();
        }
        public boolean claim(Link candidate, UUID requester, Instant at) {
            claims++; if (claimFailure != null) throw claimFailure;
            assertThat(candidate).isEqualTo(link); assertThat(requester).isEqualTo(fixture.owner); assertThat(at).isEqualTo(NOW);
            if (!wins || !"QUEUED".equals(fixture.requests.value.view().state())) return false;
            fixture.requests.state("RUNNING", null, at); return true;
        }
        public void insert(Link value) { throw new AssertionError("Dispatch cannot create or replace immutable associations"); }
    }

    private static final class MemoryRequests implements AgentStore {
        AgentSnapshot value;
        int lookups, finishes;
        boolean present = true;
        RuntimeException lookupFailure, finishFailure;
        MemoryRequests(AgentSnapshot snapshot) { value = snapshot; }
        public Optional<AgentSnapshot> find(UUID project, UUID request) {
            lookups++; if (lookupFailure != null) throw lookupFailure;
            return present && value.view().projectId().equals(project) && value.view().id().equals(request) ? Optional.of(value) : Optional.empty();
        }
        void state(String state, AgentRequestView.AgentFailure failure, Instant at) {
            var view = value.view();
            value = new AgentSnapshot(new AgentRequestView(view.id(), view.projectId(), view.requesterId(), view.traceId(), view.purpose(),
                state, view.bindings(), null, failure, null, view.createdAt(), at), value.input(), value.candidates());
        }
        public boolean finish(UUID request, AgentRequestView.AgentResult result, AgentRequestView.AgentFailure failure,
                AgentRequestView.AgentUsage usage, Instant at) {
            finishes++; if (finishFailure != null) throw finishFailure;
            assertThat(result).isNull(); assertThat(usage).isNull();
            if (!value.view().id().equals(request) || !"RUNNING".equals(value.view().state())) return false;
            state("FAILED", failure, at); return true;
        }
        public boolean insert(AgentSnapshot value, String key) { throw new AssertionError("Dispatch cannot create requests"); }
        public Optional<AgentSnapshot> findByKey(UUID p, UUID a, String key) { throw new AssertionError("No idempotency-key dispatch"); }
        public boolean claim(UUID r, Instant at) { throw new AssertionError("Never use the synthetic claim"); }
        public void setReservation(UUID r, LocalDate day, long amount) { throw new AssertionError("Dispatch cannot reserve synthetic cost"); }
        public boolean failQueued(UUID r, AgentRequestView.AgentFailure failure, Instant at) { throw new AssertionError("Cannot fail another queued executor"); }
        public boolean failRunning(UUID r, AgentRequestView.AgentFailure failure, Instant at) { throw new AssertionError("Only committed live claim may fail"); }
        public boolean hasRequests(UUID p) { throw new AssertionError("No request enumeration"); }
        public List<AgentSnapshot> expired(Instant cutoff, int limit) { throw new AssertionError("No polling or automatic recovery"); }
        public boolean expire(UUID r, Instant cutoff, AgentRequestView.AgentFailure failure, Instant at) { throw new AssertionError("No automatic expiration"); }
    }

    private static final class MemoryTransactions extends AbstractPlatformTransactionManager {
        private final MemoryRequests requests;
        private final List<AuditEvent> audit;
        int commits, rollbacks;
        Runnable afterCommit = () -> {};
        RuntimeException commitFailure;
        MemoryTransactions(MemoryRequests requests, List<AuditEvent> audit) { this.requests = requests; this.audit = audit; }
        protected Object doGetTransaction() { return new TransactionState(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            var state = (TransactionState) transaction; state.snapshot = requests.value; state.auditSize = audit.size();
        }
        protected void doCommit(DefaultTransactionStatus status) {
            if (commitFailure != null) throw commitFailure;
            commits++; afterCommit.run();
        }
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++; var state = (TransactionState) status.getTransaction(); requests.value = state.snapshot;
            while (audit.size() > state.auditSize) audit.removeLast();
        }
        private static final class TransactionState { AgentSnapshot snapshot; int auditSize; }
    }
}
