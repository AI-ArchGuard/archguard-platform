package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.agent.persistence.JdbcLiveDatabaseWork;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.AuthenticatedTaskScope;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.identity.InactiveAuthenticatedTaskException;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import({AgentPersonalEnablementIntegrationTest.Config.class, AgentLiveAccountingIntegrationTest.Config.class})
class AgentLiveDispatchIntegrationTest extends AgentLiveDatabaseIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentEnablementOperations enablements;
    @Autowired AgentStore requests;
    @Autowired LiveRequestStore links;
    @Autowired LiveAccountingOperations accounting;
    @Autowired LiveAccountingStore ledger;
    @Autowired AuditRecorder audit;
    @Autowired PlatformTransactionManager manager;
    @Autowired ProjectAuthorization projects;
    @Autowired CurrentActorProvider actors;
    @Autowired TraceIdProvider traces;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    AgentLiveAccountingIntegrationTest fixture;
    UUID request;
    AtomicReference<Instant> time;
    AtomicBoolean active;
    Clock clock;
    AuthenticatedTaskScope scope;

    @BeforeEach void prepare() throws Exception {
        fixture = new AgentLiveAccountingIntegrationTest();
        fixture.mvc = mvc; fixture.mapper = mapper; fixture.jdbc = jdbc; fixture.enablements = enablements;
        fixture.requests = requests; fixture.links = links; fixture.accounting = accounting; fixture.ledger = ledger;
        fixture.prepare();
        var batch = fixture.batch(1, 4200);
        var source = requests.find(fixture.project, fixture.request(batch.id(), true, false)).orElseThrow();
        request = UUID.randomUUID();
        var view = source.view();
        var queued = new AgentRequestView(request, view.projectId(), view.requesterId(), view.traceId(), view.purpose(),
            "QUEUED", view.bindings(), null, null, null, fixture.now, fixture.now);
        assertThat(requests.insert(new AgentSnapshot(queued, source.input(), source.candidates()), request.toString())).isTrue();
        links.insert(new LiveRequestStore.Link(request, fixture.project, batch.id(), fixture.template,
            fixture.acknowledgement, AgentPersonalEnablementIntegrationTest.CREDENTIAL,
            enablements.settings(fixture.project).revision(), fixture.now));
        org.slf4j.MDC.put("traceId", queued.traceId());
        time = new AtomicReference<>(fixture.now); active = new AtomicBoolean(true);
        clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        // Test-only capability seam, never injected into production or used to permit a model call.
        scope = new AuthenticatedTaskScope() {
            @Override public Runnable capture(UUID expected, Runnable operation) { return operation; }
            @Override public void requireActive() { if (!active.get()) throw new InactiveAuthenticatedTaskException(); }
        };
    }

    @AfterEach void clear() {
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set(null);
        SecurityContextHolder.clearContext(); org.slf4j.MDC.clear();
    }

    LiveDispatchTransitions transitions(AuditRecorder recorder) {
        return new LiveDispatchTransitions(requests, links, projects, actors, scope, traces, recorder, clock, manager);
    }
    String state() { return requests.find(fixture.project, request).orElseThrow().view().state(); }
    long audits() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.request.running'")
            .param("p", fixture.project).query(Long.class).single();
    }
    void untouchedBudgets() {
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE request_id=:r")
            .param("r", request).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.budget_usage WHERE scope_id=:p")
            .param("p", fixture.project).query(Integer.class).single()).isZero();
        assertThat(fixture.amount("PROJECT", fixture.project, "reserved_microusd")).isZero();
        assertThat(fixture.amount("PROJECT", fixture.project, "spent_microusd")).isZero();
    }

    @Test void claimAndSanitizedAuditCommitOnceWithoutReservingOrRegisteringLiveComponents() {
        var transition = transitions(audit);
        var claim = transition.start(fixture.project, request);
        assertThat(claim).isNotNull(); assertThat(claim.snapshot().view().state()).isEqualTo("RUNNING");
        assertThat(claim.toString()).doesNotContain("Synthetic evidence", "ModelInput", "VersionBindings");
        assertThat(state()).isEqualTo("RUNNING"); assertThat(audits()).isOne();
        assertThat(transition.start(fixture.project, request)).isNull(); assertThat(audits()).isOne();
        String metadata = jdbc.sql("SELECT metadata::text FROM audit.audit_records WHERE project_id=:p AND action='agent.live.request.running'")
            .param("p", fixture.project).query(String.class).single();
        assertThat(metadata).contains("requestId", "batchId", "templateId", "inputDigest", "credentialVersion")
            .doesNotContain("Synthetic evidence", "model_input", "Authorization", "apiKey");
        assertThat(context.getBeansOfType(LiveDispatchTransitions.class)).isEmpty();
        assertThat(context.getBeansOfType(LiveRequestDispatcher.class)).isEmpty();
        assertThat(context.getBeansOfType(LiveExecutionCoordinator.class)).isEmpty();
        untouchedBudgets();
    }

    @Test void auditFailureRollsBackClaimAndDoesNotCreateAttemptOrBudgetUsage() {
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set("agent.live.request.running");
        assertThatThrownBy(() -> transitions(audit).start(fixture.project, request)).isInstanceOf(AgentUnavailableException.class);
        assertThat(state()).isEqualTo("QUEUED"); assertThat(audits()).isZero(); untouchedBudgets();
    }

    @Test void expiryAfterAuditRollsBackBothAuditAndClaim() {
        AuditRecorder delayed = event -> { audit.record(event); time.set(fixture.now.plusSeconds(60)); };
        assertThatThrownBy(() -> transitions(delayed).start(fixture.project, request))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT");
        assertThat(state()).isEqualTo("QUEUED"); assertThat(audits()).isZero(); untouchedBudgets();
    }

    @Test void identityExpiryAfterAuditRollsBackClaimWithoutBlockingFutureFailurePersistence() {
        AuditRecorder delayed = event -> { audit.record(event); active.set(false); };
        assertThatThrownBy(() -> transitions(delayed).start(fixture.project, request))
            .isInstanceOf(InactiveAuthenticatedTaskException.class);
        assertThat(state()).isEqualTo("QUEUED"); assertThat(audits()).isZero(); untouchedBudgets();
    }

    @Test void wrongTraceAndExpiredOrFutureQueueCannotClaimOrAudit() {
        org.slf4j.MDC.put("traceId", "e".repeat(32));
        assertThatThrownBy(() -> transitions(audit).start(fixture.project, request)).isInstanceOf(AgentEnablementInvalidStateException.class);
        org.slf4j.MDC.put("traceId", "c".repeat(32));
        time.set(fixture.now.plusSeconds(60));
        assertThatThrownBy(() -> transitions(audit).start(fixture.project, request)).hasMessage("MODEL_TIMEOUT");
        time.set(fixture.now.minusSeconds(1));
        assertThatThrownBy(() -> transitions(audit).start(fixture.project, request)).hasMessage("MODEL_TIMEOUT");
        assertThat(state()).isEqualTo("QUEUED"); assertThat(audits()).isZero(); untouchedBudgets();
    }

    @Test void committedWinnerCanFailOnceWithoutTouchingLedgerOrRetrying() {
        var transition = transitions(audit);
        var claim = transition.start(fixture.project, request);
        active.set(false); // Expiry must not prevent failure cleanup or settlement identity reads.
        transition.fail(claim, "MODEL_UNAVAILABLE");
        transition.fail(claim, "INTERNAL_ERROR");
        assertThat(state()).isEqualTo("FAILED");
        assertThat(requests.find(fixture.project, request).orElseThrow().view().failure().code()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.result_failed'")
            .param("p", fixture.project).query(Long.class).single()).isOne();
        untouchedBudgets();
    }

    @Test void anotherTransitionsInstanceCannotUseAClaimToFailTheWinner() {
        var transition = transitions(audit);
        var claim = transition.start(fixture.project, request);
        assertThatThrownBy(() -> transitions(audit).fail(claim, "INTERNAL_ERROR"))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(state()).isEqualTo("RUNNING"); assertThat(audits()).isOne(); untouchedBudgets();
    }

    @Test void expiryWhileClaimWaitsForRowLockRollsBackInsteadOfReturningAnExecutionRight() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (var statement = blocker.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(row.next()).isTrue(); blockerPid = row.getInt(1);
            }
            try (var statement = blocker.prepareStatement("SELECT id FROM agent.requests WHERE id=? FOR UPDATE")) {
                statement.setObject(1, request); statement.executeQuery().close();
            }
            var pending = worker.submit(() -> {
                AgentLiveAccountingIntegrationTest.identity(AgentLiveAccountingIntegrationTest.OWNER);
                org.slf4j.MDC.put("traceId", "c".repeat(32));
                try { return transitions(audit).start(fixture.project, request); }
                finally { SecurityContextHolder.clearContext(); org.slf4j.MDC.clear(); }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid))")
                        .param("pid", blockerPid).query(Integer.class).single() > 0;
                    if (waiting) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
                assertThat(waiting).as("worker actually waits on the request row").isTrue();
                time.set(fixture.now.plusSeconds(61)); blocker.commit();
                assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(LiveResponsePort.CallFailure.class);
            } finally { blocker.rollback(); }
            assertThat(state()).isEqualTo("QUEUED"); assertThat(audits()).isZero(); untouchedBudgets();
        } finally {
            worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void originalBudgetCancelsClaimWhileBlockingTransactionStillHoldsItsLock() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (var statement = blocker.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(row.next()).isTrue(); blockerPid = row.getInt(1);
            }
            try (var statement = blocker.prepareStatement("SELECT id FROM agent.requests WHERE id=? FOR UPDATE")) {
                statement.setObject(1, request); statement.executeQuery().close();
            }
            var bounded = new LiveDispatchTransitions(requests, links, projects, actors, scope, traces, audit,
                clock, manager, accounting, new JdbcLiveDatabaseWork(manager, dataSource));
            var pending = worker.submit(() -> {
                AgentLiveAccountingIntegrationTest.identity(AgentLiveAccountingIntegrationTest.OWNER);
                org.slf4j.MDC.put("traceId", "c".repeat(32));
                long started = System.nanoTime();
                try { return bounded.start(fixture.project, request,
                    () -> TimeUnit.SECONDS.toNanos(2) - (System.nanoTime() - started)); }
                finally { SecurityContextHolder.clearContext(); org.slf4j.MDC.clear(); }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !pending.isDone()) {
                    waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid))")
                        .param("pid", blockerPid).query(Integer.class).single() > 0;
                    if (waiting) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
                assertThat(waiting).as("claim really blocks behind the still-open transaction").isTrue();
                assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(LiveResponsePort.CallFailure.class)
                    .satisfies(failure -> assertThat(failure.getCause()).hasMessage("MODEL_TIMEOUT").hasNoCause());
                // The blocker is deliberately not committed/released until after bounded work has exited.
                assertThat(state()).isEqualTo("QUEUED"); assertThat(audits()).isZero(); untouchedBudgets();
                assertThat(jdbc.sql("SELECT 1").query(Integer.class).single()).isOne();
            } finally { blocker.rollback(); }
        } finally {
            worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
