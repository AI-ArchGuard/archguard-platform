package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

/**
 * 验证结果与脱敏审计同事务，而已结算费用独立保留。真实 PostgreSQL 锁和审计故障覆盖提交前期限；此测试手工装配结果写入，不注册真实模型核心。
 */
@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import({AgentPersonalEnablementIntegrationTest.Config.class, AgentLiveAccountingIntegrationTest.Config.class})
class AgentLiveResultIntegrationTest extends AgentLiveDatabaseIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentEnablementOperations enablements;
    @Autowired AgentStore requests;
    @Autowired LiveRequestStore links;
    @Autowired LiveAccountingOperations accounting;
    @Autowired LiveAccountingStore ledger;
    @Autowired AuditRecorder audit;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    @Autowired LiveDatabaseWork database;
    AgentLiveAccountingIntegrationTest fixture;
    AgentSnapshot snapshot;
    AgentRequestView.AgentUsage usage;
    LiveResultTransitions writer;

    @BeforeEach void prepare() throws Exception {
        fixture = new AgentLiveAccountingIntegrationTest();
        fixture.mvc = mvc; fixture.mapper = mapper; fixture.jdbc = jdbc; fixture.enablements = enablements;
        fixture.requests = requests; fixture.links = links; fixture.accounting = accounting; fixture.ledger = ledger; fixture.prepare();
        var batch = fixture.batch(1, 4200);
        UUID request = fixture.request(batch.id(), true);
        accounting.reserve(fixture.project, batch.id(), fixture.template, request);
        accounting.settle(fixture.project, request, LiveCostPolicy.VERSION, new LiveTokenUsage(1000, 100, 500, 1100, 0));
        snapshot = requests.find(fixture.project, request).orElseThrow();
        usage = new AgentRequestView.AgentUsage(1000, 100, 50, 20L, 4200, 273L, "resp-synthetic", "deepseek-flash");
        writer = new LiveResultTransitions(requests, audit, clock, manager);
    }
    @AfterEach void clear() {
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set(null);
        SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId");
    }
    AgentRequestView.AgentResult result() {
        return new AgentRequestView.AgentResult(null, List.of(), List.of(), List.of(), List.of(), "NONE");
    }
    long resultAudits() {
        return jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action LIKE 'agent.live.result_%'")
            .param("p", fixture.project).query(Long.class).single();
    }
    // 统一检查 live 已结算金额与旧合成账本隔离，结果写入不得变更费用事实。
    void chargeStillSettled() {
        assertThat(ledger.attempt(fixture.project, snapshot.view().id()).orElseThrow().outcome()).isEqualTo("SETTLED");
        assertThat(fixture.amount("PROJECT", fixture.project, "spent_microusd")).isEqualTo(273);
        assertThat(fixture.amount("PROJECT", fixture.project, "reserved_microusd")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.budget_usage WHERE scope='PROJECT' AND scope_id=:p")
            .param("p", fixture.project).query(Integer.class).single()).isZero();
    }

    // 成功结果和元数据审计同时提交，不修改旧合成预算。
    @Test void resultAndMetadataAuditCommitTogetherWithoutSyntheticBudgetChanges() {
        writer.finishChecked(snapshot, result(), usage, () -> {});
        var view = requests.find(fixture.project, snapshot.view().id()).orElseThrow().view();
        assertThat(view.state()).isEqualTo("SUCCEEDED"); assertThat(view.usage()).isEqualTo(usage);
        assertThat(view.bindings()).isEqualTo(snapshot.view().bindings());
        assertThat(resultAudits()).isOne(); chargeStillSettled();
        String metadata = jdbc.sql("SELECT metadata::text FROM audit.audit_records WHERE project_id=:p AND action='agent.live.result_succeeded'")
            .param("p", fixture.project).query(String.class).single();
        assertThat(metadata).contains("providerResponseId", "actualModelId", "priceCatalogVersion", "273")
            .doesNotContain("Synthetic evidence", "model_input", "synthetic-placeholder");
    }

    // 结果审计失败只回滚该结果事务，既有结算不能被撤销。
    @Test void resultAuditFailureRollsBackOnlyResultAndCannotEraseSettledCharge() {
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set("agent.live.result_succeeded");
        assertThatThrownBy(() -> writer.finishChecked(snapshot, result(), usage, () -> {}))
            .isInstanceOf(AgentUnavailableException.class);
        assertThat(requests.find(fixture.project, snapshot.view().id()).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThat(resultAudits()).isZero(); chargeStillSettled();
        writer.finish(snapshot, null, new AgentRequestView.AgentFailure("INTERNAL_ERROR", "Agent request failed."), usage);
        assertThat(requests.find(fixture.project, snapshot.view().id()).orElseThrow().view().state()).isEqualTo("FAILED");
        assertThat(resultAudits()).isOne(); chargeStillSettled();
    }

    // 恢复先写终态后，迟到成功不能覆盖或重复审计。
    @Test void recoveryTerminalStateIsNeverOverwrittenOrAuditedAgain() {
        assertThat(requests.failRunning(snapshot.view().id(), new AgentRequestView.AgentFailure("INTERNAL_ERROR", "Recovered"), fixture.now)).isTrue();
        writer.finishChecked(snapshot, result(), usage, () -> {});
        assertThat(requests.find(fixture.project, snapshot.view().id()).orElseThrow().view().failure().message()).isEqualTo("Recovered");
        assertThat(resultAudits()).isZero(); chargeStillSettled();
    }

    // 等待请求行锁期间过期，解锁后也不能提交成功或审计。
    @Test void expiryWhileWaitingForResultRowLockRollsBackSuccessAndItsAudit() throws Exception {
        AtomicBoolean expired = new AtomicBoolean();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                int pid;
                try (var statement = blocker.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(row.next()).isTrue(); pid = row.getInt(1);
                }
                try (var statement = blocker.prepareStatement("SELECT id FROM agent.requests WHERE id=? FOR UPDATE")) {
                    statement.setQueryTimeout(10); statement.setObject(1, snapshot.view().id());
                    try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
                }
                Future<RuntimeException> write = executor.submit(() -> {
                    try {
                        writer.finishChecked(snapshot, result(), usage, () -> {
                            if (expired.get()) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
                        });
                        return null;
                    } catch (RuntimeException failure) { return failure; }
                });
                awaitActualBlock(pid, write);
                expired.set(true); blocker.commit();
                assertThat(write.get(10, TimeUnit.SECONDS)).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT");
            } finally { blocker.rollback(); }
        } finally {
            executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(requests.find(fixture.project, snapshot.view().id()).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThat(resultAudits()).isZero(); chargeStillSettled();
    }

    @Test void expiryDuringAuditRollsBackResultAndAuditButKeepsCharge() {
        AtomicBoolean expired = new AtomicBoolean();
        var delayed = new LiveResultTransitions(requests, event -> { audit.record(event); expired.set(true); }, clock, manager);
        assertThatThrownBy(() -> delayed.finishChecked(snapshot, result(), usage, () -> {
            if (expired.get()) throw new LiveResponsePort.CallFailure("MODEL_TIMEOUT");
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT");
        assertThat(requests.find(fixture.project, snapshot.view().id()).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThat(resultAudits()).isZero(); chargeStillSettled();
    }

    // 局部结果持久化能力不能被误认为真实模型已经装配。
    @Test void liveCoreIsNotRegisteredAndExistingSettingsStillDenyModelCalls() {
        assertThat(context.getBeansOfType(LiveExecutionCoordinator.class)).isEmpty();
        assertThat(context.getBeansOfType(LiveResponsePort.class)).isEmpty();
        assertThat(context.getBeansOfType(LiveResultTransitions.class)).isEmpty();
        assertThat(enablements.settings(fixture.project).modelCallsAvailable()).isFalse();
    }

    // 有界结果路径应在锁持有者释放前结束，费用仍保持已结算。
    @Test void boundedResultLockTimesOutBeforeHolderReleaseAndCannotEraseSettledUsage() throws Exception {
        var bounded = new LiveResultTransitions(requests, audit, clock, manager, database);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                int pid;
                try (var statement = blocker.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                    assertThat(row.next()).isTrue(); pid = row.getInt(1);
                }
                try (var lock = blocker.prepareStatement("SELECT id FROM agent.requests WHERE id=? FOR UPDATE")) {
                    lock.setQueryTimeout(10); lock.setObject(1, snapshot.view().id());
                    try (var row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
                }
                Future<RuntimeException> waiting = executor.submit(() -> {
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    try { bounded.finishBounded(snapshot, result(), usage, () -> until - System.nanoTime()); return null; }
                    catch (RuntimeException failure) { return failure; }
                });
                awaitActualBlock(pid, waiting);
                assertThat(waiting.get(5, TimeUnit.SECONDS)).isInstanceOf(LiveResponsePort.CallFailure.class)
                    .hasMessage("MODEL_TIMEOUT").hasNoCause();
                assertThat(blocker.isClosed()).isFalse();
                assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid))")
                    .param("pid", pid).query(Integer.class).single()).isZero();
            } finally { blocker.rollback(); }
        } finally {
            executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(requests.find(fixture.project, snapshot.view().id()).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThat(resultAudits()).isZero(); chargeStillSettled();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        bounded.finishBounded(snapshot, result(), usage, () -> until - System.nanoTime());
        assertThat(requests.find(fixture.project, snapshot.view().id()).orElseThrow().view().state()).isEqualTo("SUCCEEDED");
        assertThat(resultAudits()).isOne(); chargeStillSettled();
    }

    @Test void boundedExpiryDuringAuditRollsBackResultAndAuditWithoutRollingBackTheCharge() {
        var remaining = new java.util.concurrent.atomic.AtomicLong(TimeUnit.SECONDS.toNanos(5));
        var bounded = new LiveResultTransitions(requests, event -> { audit.record(event); remaining.set(0); }, clock, manager, database);
        assertThatThrownBy(() -> bounded.finishBounded(snapshot, result(), usage, remaining::get))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(requests.find(fixture.project, snapshot.view().id()).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThat(resultAudits()).isZero(); chargeStillSettled();
    }

    // 中断后的失败落库采用独立有限清理，同时保留可信用量。
    @Test void interruptedBoundedFailureFinalizationUsesIndependentCleanupAndRetainsUsage() {
        var bounded = new LiveResultTransitions(requests, audit, clock, manager, database);
        Thread.currentThread().interrupt();
        try {
            bounded.failBounded(snapshot, new AgentRequestView.AgentFailure("MODEL_TIMEOUT", "Agent request timed out."), usage);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        var terminal = requests.find(fixture.project, snapshot.view().id()).orElseThrow().view();
        assertThat(terminal.state()).isEqualTo("FAILED"); assertThat(terminal.failure().code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(terminal.usage()).isEqualTo(usage); assertThat(resultAudits()).isOne(); chargeStillSettled();
        bounded.failBounded(snapshot, new AgentRequestView.AgentFailure("INTERNAL_ERROR", "Ignored duplicate."), usage);
        assertThat(resultAudits()).isOne(); chargeStillSettled();
    }

    // 用数据库阻塞状态确认写入真正等锁，避免时序测试只验证线程启动。
    private void awaitActualBlock(int pid, Future<?> write) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertThat(write.isDone()).as("Success writer must reach the held request row").isFalse();
            int blocked = jdbc.sql("SELECT count(*) FROM pg_stat_activity a WHERE a.datname=current_database() AND a.wait_event_type='Lock' AND :pid=ANY(pg_blocking_pids(a.pid))")
                .param("pid", pid).query(Integer.class).single();
            if (blocked > 0) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.currentThread().isInterrupted()) throw new AssertionError("Lock-state observation interrupted");
        }
        throw new AssertionError("Writer did not enter expected PostgreSQL row-lock wait");
    }
}
