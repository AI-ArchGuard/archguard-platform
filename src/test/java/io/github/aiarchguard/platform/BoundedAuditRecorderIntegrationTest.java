package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.audit.persistence.JdbcAuditRecorder;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * 以真实 JDBC 事务和表锁验证独立有界审计：提交与调用方事务隔离，超时/中断回滚，连接复用不泄漏超时设置。连接获取仍可能超过预算，迟到后必须零插入。
 */
class BoundedAuditRecorderIntegrationTest extends PostgresIntegrationTestSupport {
    private static final long BUDGET = Duration.ofSeconds(5).toNanos();
    private static final String DATABASE_URL = createDatabase();
    private HikariDataSource pool;
    private JdbcClient jdbc;
    private JdbcAuditRecorder recorder;

    @BeforeEach void prepare() {
        var config = new HikariConfig(); config.setJdbcUrl(DATABASE_URL);
        config.setUsername(POSTGRES.getUsername()); config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setConnectionTimeout(1000); config.setValidationTimeout(250);
        pool = new HikariDataSource(config); jdbc = JdbcClient.create(pool);
        recorder = new JdbcAuditRecorder(jdbc, new ObjectMapper(), Clock.systemUTC(), new JdbcTransactionManager(pool), pool);
        jdbc.sql("DELETE FROM audit.audit_records").update();
    }
    @AfterEach void close() { Thread.interrupted(); if (pool != null) pool.close(); }

    // 事务级超时只作用于当前审计，回池后不能污染后续调用。
    @Test void boundedRecorderCommitsInItsOwnTransactionAndDoesNotLeakLocalTimeouts() {
        recorder.record(event(), () -> BUDGET);
        assertThat(count()).isOne();
        assertThat(setting("lock_timeout")).isEqualTo("0"); assertThat(setting("statement_timeout")).isEqualTo("0");
        assertThat(pool.getConnectionTimeout()).isEqualTo(1000);
    }

    // 意图审计独立提交，调用方随后回滚不能抹掉已发生意图。
    @Test void intentCommitSurvivesTheCallersSeparateRollback() {
        pool.setMaximumPoolSize(2);
        var caller = new org.springframework.transaction.support.TransactionTemplate(new JdbcTransactionManager(pool));
        caller.executeWithoutResult(status -> { recorder.record(event(), () -> BUDGET); status.setRollbackOnly(); });
        assertThat(count()).isOne(); assertThat(setting("statement_timeout")).isEqualTo("0");
    }

    // 序列化也消耗预算，期限失效必须回滚且不泄漏私有异常。
    @Test void deadlineExpiryDuringSerializationRollsBackTheTentativeAuditWithoutReturningPrivateErrors() {
        var remaining = new AtomicLong(BUDGET);
        var mapper = new ObjectMapper() {
            @Override public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                String json = super.writeValueAsString(value); remaining.set(0); return json;
            }
        };
        var delayed = new JdbcAuditRecorder(jdbc, mapper, Clock.systemUTC(), new JdbcTransactionManager(pool), pool);
        assertThatThrownBy(() -> delayed.record(event(), remaining::get)).hasMessage("Bounded audit unavailable").hasNoCause();
        assertThat(count()).isZero(); recorder.record(event(), () -> BUDGET); assertThat(count()).isOne();
    }

    // 锁持有者未释放时审计应先取消，并验证连接池仍能继续工作。
    @Test void blockedIntentAuditReallyCancelsWhileItsTableLockIsStillHeldAndPoolRemainsUsable() throws Exception {
        int backend = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
        var worker = Executors.newSingleThreadExecutor();
        try (var holder = DriverManager.getConnection(DATABASE_URL, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            holder.setAutoCommit(false);
            try (var lock = holder.createStatement()) { lock.execute("LOCK TABLE audit.audit_records IN ACCESS EXCLUSIVE MODE"); }
            var pending = worker.submit(() -> recorder.record(event(), () -> TimeUnit.SECONDS.toNanos(2)));
            boolean observed = false; long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            try (var inspect = holder.prepareStatement("SELECT cardinality(pg_blocking_pids(?))")) {
                inspect.setInt(1, backend);
                while (System.nanoTime() < until) {
                    try (var rows = inspect.executeQuery()) { rows.next(); if (rows.getInt(1) > 0) { observed = true; break; } }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
            }
            assertThat(observed).as("server observed the audit INSERT waiting on the held lock").isTrue();
            assertThatThrownBy(() -> pending.get(3, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getCause()).hasMessage("Bounded audit unavailable").hasNoCause());
            assertThat(holder.isClosed()).isFalse(); holder.rollback();
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
        assertThat(count()).isZero(); recorder.record(event(), () -> BUDGET); assertThat(count()).isOne();
    }

    @Test void expiredOrMalformedBudgetsRejectBeforeInsertAndInterruptIsPreserved() {
        for (long budget : new long[]{0, -1, TimeUnit.SECONDS.toNanos(30) + 1}) {
            assertThatThrownBy(() -> recorder.record(event(), () -> budget)).hasMessage("Bounded audit unavailable").hasNoCause();
        }
        assertThatThrownBy(() -> recorder.record(event(), null)).hasMessage("Bounded audit unavailable").hasNoCause();
        assertThatThrownBy(() -> recorder.record(event(), () -> { throw new IllegalStateException("private synthetic detail"); }))
            .hasMessage("Bounded audit unavailable").hasNoCause();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> recorder.record(event(), () -> BUDGET)).hasMessage("Bounded audit unavailable").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(count()).isZero();
    }

    // 连接可能迟到，但借到后预算已过期时不得插入，也不改共享池配置。
    @Test void lateConnectionAcquisitionRunsZeroInsertAndDoesNotMutateSharedPoolConfiguration() throws Exception {
        var worker = Executors.newSingleThreadExecutor(); var end = new AtomicLong();
        try (var held = pool.getConnection()) {
            end.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150));
            var pending = worker.submit(() -> recorder.record(event(), () -> end.get() - System.nanoTime()));
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
            assertThat(pending.isDone()).as("pool acquisition retains its finite independently configured wait").isFalse();
            held.close();
            assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getCause()).hasNoCause());
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
        assertThat(count()).isZero(); assertThat(pool.getConnectionTimeout()).isEqualTo(1000);
    }

    // 旧构造仍可支持原接口，但不能被新有界调用当作兼容回退。
    @Test void legacyConstructorKeepsLegacyRecordingButCannotFallBackForBoundedRecording() {
        var legacy = new JdbcAuditRecorder(jdbc, new ObjectMapper(), Clock.systemUTC());
        legacy.record(event()); assertThat(count()).isOne();
        assertThatThrownBy(() -> legacy.record(event(), () -> BUDGET)).hasMessage("Bounded audit unavailable").hasNoCause();
        assertThat(count()).isOne();
    }

    @Test void mismatchedTransactionConnectionDomainAndPrivateSerializationErrorsAreSanitized() {
        var wrong = new JdbcAuditRecorder(jdbc, new ObjectMapper(), Clock.systemUTC(), new JdbcTransactionManager(), pool);
        assertThatThrownBy(() -> wrong.record(event(), () -> BUDGET)).hasMessage("Bounded audit unavailable").hasNoCause();
        assertThatThrownBy(() -> recorder.record(null, () -> BUDGET)).hasMessage("Bounded audit unavailable").hasNoCause();
        assertThat(count()).isZero();
    }

    private static AuditEvent event() {
        return new AuditEvent(UUID.randomUUID(), null, "AGENT_CREDENTIAL_USE_REQUESTED", AuditResult.SUCCESS,
            "b".repeat(32), Map.of("provider", "DEEPSEEK", "credentialVersion", UUID.randomUUID().toString()));
    }
    private int count() { return jdbc.sql("SELECT count(*) FROM audit.audit_records").query(Integer.class).single(); }
    private String setting(String key) { return jdbc.sql("SELECT current_setting(:key)").param("key", key).query(String.class).single(); }

    private static String createDatabase() {
        String name = "bounded_audit_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
            String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
            Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
            return url;
        } catch (Exception failure) { throw new IllegalStateException("Synthetic audit test database unavailable", failure); }
    }
}
