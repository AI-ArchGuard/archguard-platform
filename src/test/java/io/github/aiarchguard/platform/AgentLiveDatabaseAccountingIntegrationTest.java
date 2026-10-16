package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import java.sql.Connection;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true",
    "archguard.agent.recovery-delay=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import({AgentPersonalEnablementIntegrationTest.Config.class, AgentLiveAccountingIntegrationTest.Config.class})
class AgentLiveDatabaseAccountingIntegrationTest extends AgentLiveDatabaseIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentEnablementOperations enablements;
    @Autowired AgentStore requests;
    @Autowired LiveRequestStore links;
    @Autowired LiveAccountingOperations accounting;
    @Autowired LiveAccountingStore ledger;
    @Autowired DataSource dataSource;
    private AgentLiveAccountingIntegrationTest fixture;

    @BeforeEach void prepare() throws Exception {
        fixture = new AgentLiveAccountingIntegrationTest();
        fixture.mvc = mvc; fixture.mapper = mapper; fixture.jdbc = jdbc; fixture.enablements = enablements;
        fixture.requests = requests; fixture.links = links; fixture.accounting = accounting; fixture.ledger = ledger;
        fixture.prepare();
        // Keep wall time fixed: the budget under test is monotonic, not the shared recovery clock.
    }
    @AfterEach void clear() {
        Thread.interrupted(); SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId");
    }

    @Test void boundedDefaultsRejectWithoutCallingLegacyImplementations() {
        var legacyCalls = new AtomicInteger();
        LiveAccountingOperations legacy = new LiveAccountingOperations() {
            @Override public String preview(UUID p, List<UUID> ids) { throw new AssertionError(); }
            @Override public LiveBatchView approve(UUID p, String o, boolean s, LiveBatchInput i) { throw new AssertionError(); }
            @Override public LiveBatchView get(UUID p, UUID b) { throw new AssertionError(); }
            @Override public LiveBatchView revoke(UUID p, UUID b, String o, boolean s) { throw new AssertionError(); }
            @Override public String requestDigest(UUID p, UUID b, UUID t) { throw new AssertionError(); }
            @Override public Admission reserve(UUID p, UUID b, UUID t, UUID r) { legacyCalls.incrementAndGet(); throw new AssertionError(); }
            @Override public SendApproval checkBeforeSend(UUID p, UUID b, UUID t, UUID r) { legacyCalls.incrementAndGet(); throw new AssertionError(); }
            @Override public Outcome settle(UUID p, UUID r, String v, LiveTokenUsage u) { throw new AssertionError(); }
            @Override public Outcome unknown(UUID p, UUID r) { throw new AssertionError(); }
        };
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> legacy.reserve(id, id, id, id, deadline(2))).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThatThrownBy(() -> legacy.checkBeforeSend(id, id, id, id, deadline(2))).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(legacyCalls.get()).isZero();
    }

    @Test void boundedReserveAndSendCheckKeepTheExistingFrozenAccountingBindings() {
        var batch = fixture.batch(1, 4200); UUID request = fixture.request(batch.id(), true);
        var remaining = deadline(5);
        assertThat(accounting.reserve(fixture.project, batch.id(), fixture.template, request, remaining).newAttempt()).isTrue();
        var checked = accounting.checkBeforeSend(fixture.project, batch.id(), fixture.template, request, remaining);
        assertThat(checked.credentialVersion()).isEqualTo(AgentPersonalEnablementIntegrationTest.CREDENTIAL);
        assertThat(checked.allowedResponseModels()).containsExactly("deepseek-flash");
        fixture.assertPendingReservation(batch.id(), request);
    }

    @Test void boundedReserveAndSendCheckExplicitlyPropagateTheOriginalBudgetToCredentialMetadata() {
        var batch = fixture.batch(1, 4200); UUID request = fixture.request(batch.id(), true);
        var remaining = deadline(5);
        AgentPersonalEnablementIntegrationTest.BOUNDED_METADATA_READS.set(0);
        AgentPersonalEnablementIntegrationTest.METADATA_BUDGET.set(null);
        AgentPersonalEnablementIntegrationTest.REQUIRE_BOUNDED_METADATA.set(true);
        try {
            assertThat(accounting.reserve(fixture.project, batch.id(), fixture.template, request, remaining).newAttempt()).isTrue();
            assertThat(AgentPersonalEnablementIntegrationTest.BOUNDED_METADATA_READS.get()).isGreaterThanOrEqualTo(2);
            assertThat(AgentPersonalEnablementIntegrationTest.METADATA_BUDGET.get()).isSameAs(remaining);
            int before = AgentPersonalEnablementIntegrationTest.BOUNDED_METADATA_READS.get();
            var checked = accounting.checkBeforeSend(fixture.project, batch.id(), fixture.template, request, remaining);
            assertThat(checked.credentialVersion()).isEqualTo(AgentPersonalEnablementIntegrationTest.CREDENTIAL);
            assertThat(AgentPersonalEnablementIntegrationTest.BOUNDED_METADATA_READS.get()).isGreaterThan(before);
            assertThat(AgentPersonalEnablementIntegrationTest.METADATA_BUDGET.get()).isSameAs(remaining);
            fixture.assertPendingReservation(batch.id(), request);
        } finally { AgentPersonalEnablementIntegrationTest.REQUIRE_BOUNDED_METADATA.set(false); }
    }

    @Test void settingsLockTimesOutBoundedReserveBeforeHolderIsReleased() throws Exception {
        var batch = fixture.batch(1, 4200); UUID request = fixture.request(batch.id(), true);
        assertLockWaitEndsBeforeRelease("SELECT project_id FROM agent.project_settings WHERE project_id=? FOR UPDATE", fixture.project,
            () -> accounting.reserve(fixture.project, batch.id(), fixture.template, request, deadline(2)));
        assertNoReservation(batch.id(), request);
    }

    @Test void budgetLockTimesOutAndRollsBackAllThreeReservationsBeforeHolderIsReleased() throws Exception {
        var batch = fixture.batch(1, 4200); UUID request = fixture.request(batch.id(), true);
        fixture.seedBudget("DEPLOYMENT", AgentLiveAccountingIntegrationTest.DEPLOYMENT, 0);
        var day = fixture.now.atZone(ZoneOffset.UTC).toLocalDate();
        assertLockWaitEndsBeforeRelease("SELECT scope_id FROM agent.live_budget_usage WHERE scope='DEPLOYMENT' AND scope_id=? AND utc_day=DATE '"
            + day + "' FOR UPDATE", AgentLiveAccountingIntegrationTest.DEPLOYMENT,
            () -> accounting.reserve(fixture.project, batch.id(), fixture.template, request, deadline(2)));
        assertNoReservation(batch.id(), request);
    }

    @Test void settingsLockTimesOutBoundedSendCheckWithoutReleasingOrChargingTheAttempt() throws Exception {
        var batch = fixture.batch(1, 4200); UUID request = fixture.request(batch.id(), true);
        assertThat(accounting.reserve(fixture.project, batch.id(), fixture.template, request, deadline(5)).newAttempt()).isTrue();
        assertLockWaitEndsBeforeRelease("SELECT project_id FROM agent.project_settings WHERE project_id=? FOR UPDATE", fixture.project,
            () -> accounting.checkBeforeSend(fixture.project, batch.id(), fixture.template, request, deadline(2)));
        fixture.assertPendingReservation(batch.id(), request);
    }

    @Test void elapsedExecutionAndInterruptedCallerStillSettleKnownUsageAfterRecoveryAndRevocation() {
        var batch = fixture.batch(1, 4200); UUID request = fixture.request(batch.id(), true);
        assertThat(accounting.reserve(fixture.project, batch.id(), fixture.template, request, deadline(5)).newAttempt()).isTrue();
        assertThatThrownBy(() -> accounting.checkBeforeSend(fixture.project, batch.id(), fixture.template, request, () -> 0L))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(requests.expire(request, fixture.now.plusSeconds(1),
            new AgentRequestView.AgentFailure("INTERNAL_ERROR", "Synthetic recovery"), fixture.now.plusSeconds(61))).isTrue();
        enablements.revoke(fixture.project, fixture.acknowledgement);
        AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.set(new CredentialStatus(false, null, null));
        jdbc.sql("DELETE FROM project.project_members WHERE project_id=:p").param("p", fixture.project).update();
        Thread.currentThread().interrupt();
        try {
            var outcome = accounting.settle(fixture.project, request, LiveCostPolicy.VERSION, new LiveTokenUsage(1000, 100, 500, 1100, 0));
            assertThat(outcome.state()).isEqualTo("SETTLED"); assertThat(outcome.actualMicrousd()).isEqualTo(273);
            assertThat(Thread.currentThread().isInterrupted()).as("cleanup must restore the caller interrupt").isTrue();
        } finally { Thread.interrupted(); }
        assertThat(requests.find(fixture.project, request).orElseThrow().view().state()).isEqualTo("FAILED");
        assertThat(fixture.amount("PROJECT", fixture.project, "reserved_microusd")).isZero();
        assertThat(fixture.amount("PROJECT", fixture.project, "spent_microusd")).isEqualTo(273);
        assertThat(fixture.amount("DEPLOYMENT", AgentLiveAccountingIntegrationTest.DEPLOYMENT, "spent_microusd")).isEqualTo(273);
        assertThat(accounting.settle(fixture.project, request, LiveCostPolicy.VERSION, new LiveTokenUsage(1, 1, 0, 2, 0)).actualMicrousd()).isEqualTo(273);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_outcomes WHERE request_id=:id").param("id", request).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.finished'")
            .param("p", fixture.project).query(Integer.class).single()).isOne();
    }

    @Test void interruptedUnknownFinalizationRetainsTheReservationExactlyOnce() {
        var batch = fixture.batch(1, 4200); UUID request = fixture.request(batch.id(), true);
        accounting.reserve(fixture.project, batch.id(), fixture.template, request, deadline(5));
        Thread.currentThread().interrupt();
        try {
            assertThat(accounting.unknown(fixture.project, request).state()).isEqualTo("UNKNOWN");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(accounting.unknown(fixture.project, request).state()).isEqualTo("UNKNOWN");
        assertThat(fixture.amount("PROJECT", fixture.project, "reserved_microusd")).isEqualTo(4200);
        assertThat(fixture.amount("PROJECT", fixture.project, "spent_microusd")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_outcomes WHERE request_id=:id").param("id", request).query(Integer.class).single()).isOne();
    }

    private void assertNoReservation(UUID batch, UUID request) {
        assertThat(ledger.attempt(fixture.project, request)).isEmpty();
        assertThat(fixture.amount("PROJECT", fixture.project, "reserved_microusd")).isZero();
        assertThat(fixture.amount("DEPLOYMENT", AgentLiveAccountingIntegrationTest.DEPLOYMENT, "reserved_microusd")).isZero();
        assertThat(jdbc.sql("SELECT reserved_microusd+spent_microusd FROM agent.live_batch_usage WHERE batch_id=:id")
            .param("id", batch).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT attempts FROM agent.live_batch_usage WHERE batch_id=:id")
            .param("id", batch).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.reserved'")
            .param("p", fixture.project).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.write_rejected'")
            .param("p", fixture.project).query(Integer.class).single()).isOne();
    }

    private void assertLockWaitEndsBeforeRelease(String sql, UUID row, Supplier<?> operation) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid;
                try (var query = holder.createStatement(); var result = query.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(result.next()).isTrue(); holderPid = result.getInt(1);
                }
                try (var lock = holder.prepareStatement(sql)) {
                    lock.setQueryTimeout(10); lock.setObject(1, row);
                    try (var result = lock.executeQuery()) { assertThat(result.next()).isTrue(); }
                }
                Future<RuntimeException> waiting = executor.submit(() -> {
                    AgentLiveAccountingIntegrationTest.identity(AgentLiveAccountingIntegrationTest.OWNER);
                    try { operation.get(); return null; }
                    catch (RuntimeException denied) { return denied; }
                    finally { SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId"); }
                });
                awaitPostgresLock(holderPid, waiting);
                // Crucially, the holder is still open: cancellation, not lock release, ends the wait.
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isInstanceOf(LiveResponsePort.CallFailure.class)
                    .hasMessage("MODEL_TIMEOUT").hasNoCause();
                assertThat(holder.isClosed()).isFalse();
                assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :holder=ANY(pg_blocking_pids(pid))")
                    .param("holder", holderPid).query(Integer.class).single()).isZero();
            } finally { holder.rollback(); }
        } finally {
            executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitPostgresLock(int holderPid, Future<?> waiting) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            assertThat(waiting.isDone()).as("operation must reach a real PostgreSQL lock wait").isFalse();
            int count = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND :holder=ANY(pg_blocking_pids(pid))")
                .param("holder", holderPid).query(Integer.class).single();
            if (count > 0) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            if (Thread.currentThread().isInterrupted()) throw new AssertionError("lock observation interrupted");
        }
        throw new AssertionError("operation did not wait on the held PostgreSQL row");
    }
    private static LongSupplier deadline(int seconds) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        return () -> until - System.nanoTime();
    }
}
