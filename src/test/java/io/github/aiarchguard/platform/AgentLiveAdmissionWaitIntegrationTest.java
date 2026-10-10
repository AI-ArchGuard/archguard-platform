package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.AgentEnablementOperations;
import io.github.aiarchguard.platform.agent.internal.AgentStore;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingOperations;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingStore;
import io.github.aiarchguard.platform.agent.internal.SyntheticBatchInventory;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import({AgentPersonalEnablementIntegrationTest.Config.class, AgentLiveAdmissionWaitIntegrationTest.Config.class})
class AgentLiveAdmissionWaitIntegrationTest extends PostgresIntegrationTestSupport {
    private static final AtomicReference<SyntheticBatchInventory.Proof> REVIEW = new AtomicReference<>();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentEnablementOperations enablements;
    @Autowired AgentStore requests;
    @Autowired LiveAccountingOperations accounting;
    @Autowired LiveAccountingStore ledger;
    @Autowired DataSource dataSource;
    private AgentLiveAccountingIntegrationTest fixture;

    @BeforeEach void prepare() throws Exception {
        fixture = new AgentLiveAccountingIntegrationTest();
        fixture.mvc = mvc; fixture.mapper = mapper; fixture.jdbc = jdbc; fixture.enablements = enablements;
        fixture.requests = requests; fixture.accounting = accounting; fixture.ledger = ledger;
        fixture.prepare(); reviewAt(fixture.now);
    }
    @AfterEach void clear() {
        REVIEW.set(null); SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId");
    }

    @Test void changedInventoryWhileWaitingCannotWriteAnAttemptOrCharge() throws Exception {
        deniedAfterBudgetWait(() -> AgentLiveAccountingIntegrationTest.INVENTORY_SHA.set("9".repeat(64)));
    }
    @Test void expiredBatchWhileWaitingCannotWriteAnAttemptOrCharge() throws Exception {
        deniedAfterBudgetWait(() -> AgentPersonalEnablementIntegrationTest.TIME.set(fixture.now.plusSeconds(3600)));
    }
    @Test void changedCredentialWhileWaitingCannotWriteAnAttemptOrCharge() throws Exception {
        deniedAfterBudgetWait(() -> AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE
            .set(new CredentialStatus(true, UUID.randomUUID(), fixture.now)));
    }
    @Test void utcRolloverWhileWaitingFailsEvenWhenAllApprovalsRemainValid() throws Exception {
        fixture.now = fixture.now.atZone(ZoneOffset.UTC).toLocalDate().atTime(LocalTime.of(23, 59, 59)).toInstant(ZoneOffset.UTC);
        AgentPersonalEnablementIntegrationTest.TIME.set(fixture.now); reviewAt(fixture.now);
        deniedAfterBudgetWait(() -> {
            Instant nextDay = fixture.now.plusSeconds(1);
            AgentPersonalEnablementIntegrationTest.TIME.set(nextDay);
            assertThat(REVIEW.get().expiresAt()).isAfter(nextDay);
            assertThat(REVIEW.get().reviewedAt()).isEqualTo(fixture.now);
        });
    }

    private void deniedAfterBudgetWait(Runnable invalidate) throws Exception {
        var batch = fixture.batch(1, 4200); UUID request = fixture.request(batch.id(), true);
        LocalDate day = fixture.now.atZone(ZoneOffset.UTC).toLocalDate();
        fixture.seedBudget("DEPLOYMENT", AgentLiveAccountingIntegrationTest.DEPLOYMENT, 0);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                int blockerPid;
                try (var statement = blocker.createStatement()) {
                    statement.setQueryTimeout(10);
                    try (var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                        assertThat(result.next()).isTrue(); blockerPid = result.getInt(1);
                    }
                }
                try (var statement = blocker.prepareStatement("""
                        SELECT reserved_microusd FROM agent.live_budget_usage
                        WHERE scope='DEPLOYMENT' AND scope_id=? AND utc_day=? FOR UPDATE
                        """)) {
                    statement.setQueryTimeout(10); statement.setObject(1, AgentLiveAccountingIntegrationTest.DEPLOYMENT);
                    statement.setObject(2, day);
                    try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
                }
                Future<RuntimeException> attempt = executor.submit(() -> {
                    AgentLiveAccountingIntegrationTest.identity(AgentLiveAccountingIntegrationTest.OWNER);
                    try {
                        accounting.reserve(fixture.project, batch.id(), fixture.template, request); return null;
                    } catch (RuntimeException rejected) { return rejected; }
                    finally { SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId"); }
                });
                awaitActualBlock(blockerPid, attempt);
                invalidate.run(); blocker.commit();
                assertThat(attempt.get(10, TimeUnit.SECONDS)).isInstanceOf(AgentEnablementInvalidStateException.class);
            } finally { blocker.rollback(); }
        } finally {
            executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE project_id=:p")
            .param("p", fixture.project).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT reserved_microusd+spent_microusd FROM agent.live_batch_usage WHERE batch_id=:b")
            .param("b", batch.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT attempts FROM agent.live_batch_usage WHERE batch_id=:b")
            .param("b", batch.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COALESCE(sum(reserved_microusd+spent_microusd),0) FROM agent.live_budget_usage WHERE scope='PROJECT' AND scope_id=:p")
            .param("p", fixture.project).query(Long.class).single()).isZero();
        for (LocalDate date : List.of(day, day.plusDays(1))) {
            assertThat(jdbc.sql("""
                    SELECT COALESCE(sum(reserved_microusd+spent_microusd),0) FROM agent.live_budget_usage
                    WHERE scope='DEPLOYMENT' AND scope_id=:d AND utc_day=:day
                    """).param("d", AgentLiveAccountingIntegrationTest.DEPLOYMENT).param("day", date).query(Long.class).single()).isZero();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.reserved'")
            .param("p", fixture.project).query(Integer.class).single()).isZero();
    }

    private void awaitActualBlock(int blockerPid, Future<?> attempt) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertThat(attempt.isDone()).as("Admission must reach the held deployment budget row").isFalse();
            int waiting = jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity a WHERE a.datname=current_database()
                    AND a.wait_event_type='Lock' AND :blocker=ANY(pg_blocking_pids(a.pid))
                """).param("blocker", blockerPid).query(Integer.class).single();
            if (waiting > 0) return;
            // Poll actual PostgreSQL lock state, rather than assuming a fixed delay establishes ordering.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.currentThread().isInterrupted()) throw new AssertionError("Lock-state observation was interrupted");
        }
        throw new AssertionError("Admission did not enter the expected PostgreSQL lock wait within 10 seconds");
    }
    private static void reviewAt(Instant now) {
        REVIEW.set(new SyntheticBatchInventory.Proof("0.1.0", "e".repeat(64), "synthetic-v1", "f".repeat(64),
            "synthetic-review", now, now.plusSeconds(3600)));
    }
    @TestConfiguration static class Config {
        @Bean @Primary SyntheticBatchInventory reviewedSyntheticInventory() {
            return (project, inventory, digest) -> {
                var proof = REVIEW.get();
                return proof != null && AgentLiveAccountingIntegrationTest.INVENTORY.contains(project + ":" + digest)
                    ? Optional.of(new SyntheticBatchInventory.Proof(proof.schemaVersion(), AgentLiveAccountingIntegrationTest.INVENTORY_SHA.get(),
                        proof.fixtureSetVersion(), proof.fixtureArtifactSha256(), proof.reviewRef(), proof.reviewedAt(), proof.expiresAt()))
                    : Optional.empty();
            };
        }
    }
}
