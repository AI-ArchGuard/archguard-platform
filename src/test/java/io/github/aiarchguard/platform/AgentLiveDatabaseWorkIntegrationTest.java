package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.aiarchguard.platform.agent.AgentQuotaExceededException;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import io.github.aiarchguard.platform.agent.persistence.JdbcLiveDatabaseWork;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 直接以 JDBC 与真实 PostgreSQL 验证有界数据库事务：事务局部超时、只读状态、回滚、长 SQL/行锁取消及连接复用。连接获取可能超出预算，本测试只保证迟到借用后零动作。
 */
class AgentLiveDatabaseWorkIntegrationTest extends PostgresIntegrationTestSupport {
    private static final long FIVE_SECONDS = Duration.ofSeconds(5).toNanos();
    private static final String PRIVATE = "private-synthetic-db-error-not-a-secret";
    HikariDataSource pool;
    JdbcClient jdbc;
    JdbcLiveDatabaseWork work;
    String table;

    @BeforeEach void prepare() {
        var config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl()); config.setUsername(POSTGRES.getUsername()); config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(1); config.setMinimumIdle(0); config.setConnectionTimeout(1_000); config.setValidationTimeout(250);
        pool = new HikariDataSource(config); jdbc = JdbcClient.create(pool);
        work = new JdbcLiveDatabaseWork(new JdbcTransactionManager(pool), pool);
        table = "dbwork_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.sql("CREATE TABLE " + table + " (id integer PRIMARY KEY, value integer NOT NULL)").update();
    }

    @AfterEach void close() {
        Thread.interrupted();
        if (pool != null) {
            try { if (table != null) jdbc.sql("DROP TABLE " + table).update(); }
            finally { pool.close(); }
        }
    }

    // 实际事务使用非零 SQL/锁超时，提交后恢复连接原设置。
    @Test void commitsAndUsesNonzeroTransactionLocalTimeoutsWithoutLeakingToThePool() {
        String originalLock = setting("lock_timeout"), originalStatement = setting("statement_timeout");
        int backend = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
        int inserted = work.transaction(() -> FIVE_SECONDS, false, () -> {
            assertThat(setting("lock_timeout")).isNotEqualTo("0");
            assertThat(setting("statement_timeout")).isNotEqualTo("0");
            assertThat(setting("transaction_read_only")).isEqualTo("off");
            return jdbc.sql("INSERT INTO " + table + " VALUES (1,10)").update();
        });
        assertThat(inserted).isOne(); assertThat(count()).isOne();
        assertThat(setting("lock_timeout")).isEqualTo(originalLock);
        assertThat(setting("statement_timeout")).isEqualTo(originalStatement);
        assertThat(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single()).isEqualTo(backend);
        assertThat(pool.getConnectionTimeout()).isEqualTo(1_000);
    }

    // 只读必须施加在当前数据库事务，回池后不影响下一次使用。
    @Test void readOnlyIsAppliedToTheActualTransactionAndResetBeforePoolReuse() {
        assertThat(work.transaction(() -> FIVE_SECONDS, true, () -> setting("transaction_read_only"))).isEqualTo("on");
        assertThat(setting("transaction_read_only")).isEqualTo("off");
        assertThat(count()).isZero();
    }

    @Test void knownBusinessFailureRollsBackAndRetainsOnlyItsStableCategory() {
        var quota = new AgentQuotaExceededException(); quota.initCause(new IllegalStateException(PRIVATE));
        quota.addSuppressed(new IllegalStateException(PRIVATE));
        assertThatThrownBy(() -> work.transaction(() -> FIVE_SECONDS, false, () -> {
            jdbc.sql("INSERT INTO " + table + " VALUES (1,10)").update(); throw quota;
        })).isInstanceOf(AgentQuotaExceededException.class).hasNoCause()
            .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
        assertThat(count()).isZero(); assertThat(setting("statement_timeout")).isEqualTo("0");
    }

    // 日常状态拒绝必须保留固定分类；数据库端口不能将其伪装为未知存储故障。
    @Test void dailyStateFailureRollsBackAndPreservesOnlyFixedSafeCategory() {
        var failure = new io.github.aiarchguard.platform.agent.AgentDailyStateInvalidException();
        failure.initCause(new IllegalStateException(PRIVATE)); failure.addSuppressed(new IllegalStateException(PRIVATE));
        assertThatThrownBy(() -> work.transaction(() -> FIVE_SECONDS, false, () -> {
            jdbc.sql("INSERT INTO " + table + " VALUES (1,10)").update(); throw failure;
        })).isInstanceOf(io.github.aiarchguard.platform.agent.AgentDailyStateInvalidException.class)
            .hasMessage("Agent daily state is not valid for this operation").hasNoCause()
            .satisfies(safe -> assertThat(safe.getSuppressed()).isEmpty());
        assertThat(count()).isZero(); assertThat(setting("statement_timeout")).isEqualTo("0");
    }

    // 动作执行过后预算失效也须回滚，不能按动作起始时间放行提交。
    @Test void expiryAfterActionRollsBackAndDoesNotReuseAnExpiredBudget() {
        var remaining = new AtomicLong(FIVE_SECONDS);
        assertThatThrownBy(() -> work.transaction(remaining::get, false, () -> {
            jdbc.sql("INSERT INTO " + table + " VALUES (1,10)").update(); remaining.set(0); return 1;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(count()).isZero();
    }

    // 其他提交前回调耗尽预算时，最后期限检查仍需挡住提交。
    @Test void expiryInAnEarlierBeforeCommitCallbackRollsBackTheTentativeAction() {
        var remaining = new AtomicLong(FIVE_SECONDS);
        assertThatThrownBy(() -> work.transaction(remaining::get, false, () -> {
            jdbc.sql("INSERT INTO " + table + " VALUES (1,10)").update();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { remaining.set(0); }
            });
            return 1;
        })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(count()).isZero();
    }

    // 取消真正阻塞的 SQL，再验证同池连接可继续查询。
    @Test void realLongStatementIsCancelledBeforeItFinishesAndTheConnectionRemainsReusable() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> work.transaction(() -> Duration.ofMillis(400).toNanos(), true,
            () -> jdbc.sql("SELECT pg_sleep(3)").query((row, index) -> 1).single()))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(jdbc.sql("SELECT 1").query(Integer.class).single()).isOne();
        assertThat(setting("statement_timeout")).isEqualTo("0");
    }

    // 锁仍被持有时等待方必须超时，不能先等解锁再声称取消。
    @Test void realRowLockWaitEndsBeforeTheHolderReleasesAndDoesNotApplyTheUpdate() throws Exception {
        jdbc.sql("INSERT INTO " + table + " VALUES (1,10)").update();
        var worker = Executors.newSingleThreadExecutor();
        try (Connection blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            blocker.setAutoCommit(false);
            int blockerPid;
            try (var statement = blocker.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertThat(row.next()).isTrue(); blockerPid = row.getInt(1);
            }
            try (var statement = blocker.createStatement(); var row = statement.executeQuery("SELECT id FROM " + table + " WHERE id=1 FOR UPDATE")) {
                assertThat(row.next()).isTrue();
            }
            var pending = worker.submit(() -> work.transaction(() -> Duration.ofMillis(700).toNanos(), false,
                () -> jdbc.sql("UPDATE " + table + " SET value=20 WHERE id=1").update()));
            try {
                boolean waiting = false;
                long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (System.nanoTime() < until) {
                    try (var statement = blocker.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))")) {
                        statement.setInt(1, blockerPid);
                        try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); waiting = rows.getInt(1) > 0; }
                    }
                    if (waiting) break;
                    LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                }
                assertThat(waiting).as("SQL actually waited on the held row lock").isTrue();
                assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(LiveResponsePort.CallFailure.class)
                    .satisfies(failure -> assertThat(failure.getCause()).hasMessage("MODEL_TIMEOUT").hasNoCause());
            } finally { blocker.rollback(); }
        } finally {
            worker.shutdownNow(); assertThat(worker.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.sql("SELECT value FROM " + table + " WHERE id=1").query(Integer.class).single()).isEqualTo(10);
        assertThat(setting("lock_timeout")).isEqualTo("0");
    }

    @Test void invalidOrBrokenBudgetsNeverRunTheActionOrAlterSharedPoolConfiguration() {
        var calls = new AtomicInteger();
        assertThatThrownBy(() -> work.transaction(null, false, calls::incrementAndGet)).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        for (long expired : new long[] {0, -1}) {
            assertThatThrownBy(() -> work.transaction(() -> expired, false, calls::incrementAndGet))
                .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        }
        for (long invalid : new long[] {Duration.ofSeconds(31).toNanos(), Long.MAX_VALUE}) {
            assertThatThrownBy(() -> work.transaction(() -> invalid, false, calls::incrementAndGet))
                .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        }
        assertThatThrownBy(() -> work.transaction(() -> { throw new IllegalStateException(PRIVATE); }, false, calls::incrementAndGet))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThatThrownBy(() -> work.transaction(() -> FIVE_SECONDS, false, null)).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(calls).hasValue(0); assertThat(pool.getConnectionTimeout()).isEqualTo(1_000); assertThat(count()).isZero();
    }

    // 明确连接池获取的限制：取得连接时已过期，就不得执行数据库动作。
    @Test void connectionAcquisitionMayOutlastItsBudgetButAnExpiredBorrowRunsZeroAction() throws Exception {
        var calls = new AtomicInteger(); var worker = Executors.newSingleThreadExecutor();
        try (var held = pool.getConnection()) {
            var pending = worker.submit(() -> work.transaction(() -> Duration.ofMillis(150).toNanos(), false, calls::incrementAndGet));
            long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0 && System.nanoTime() < until) {
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            }
            assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isOne();
            long waitingStarted = System.nanoTime();
            while (System.nanoTime() - waitingStarted < Duration.ofMillis(250).toNanos()) LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            assertThat(pending.isDone()).as("the existing pool wait is not silently advertised as a per-execution bound").isFalse();
            held.close();
            assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(LiveResponsePort.CallFailure.class)
                .satisfies(failure -> assertThat(failure.getCause()).hasMessage("MODEL_TIMEOUT").hasNoCause());
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
        assertThat(calls).hasValue(0); assertThat(pool.getConnectionTimeout()).isEqualTo(1_000); assertThat(count()).isZero();
    }

    // 失败清理使用新的有限事务，允许保留原调用方中断状态。
    @Test void cleanupUsesAnIndependentFiniteTransactionAndRestoresAnExistingInterrupt() {
        assertThatThrownBy(() -> work.transaction(() -> 0, false, () -> 0)).hasMessage("MODEL_TIMEOUT");
        Thread.currentThread().interrupt();
        try {
            int inserted = work.cleanup(() -> jdbc.sql("INSERT INTO " + table + " VALUES (1,10)").update());
            assertThat(inserted).isOne(); assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(count()).isOne();
    }

    @Test void newInterruptDuringCleanupRollsBackAndIsNotLost() {
        try {
            assertThatThrownBy(() -> work.cleanup(() -> {
                jdbc.sql("INSERT INTO " + table + " VALUES (1,10)").update(); Thread.currentThread().interrupt(); return 1;
            })).isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(count()).isZero();
    }

    // 不可信 JDBC/回调异常只向外保留稳定类别，不带 SQL 细节或私有原因。
    @Test void unknownCallbackAndDatabaseFailuresNeverReturnSqlDetailsMessagesOrCauses() {
        assertThatThrownBy(() -> work.transaction(() -> FIVE_SECONDS, false,
            () -> { throw new IllegalStateException(PRIVATE, new IllegalArgumentException(PRIVATE)); }))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThatThrownBy(() -> work.transaction(() -> FIVE_SECONDS, false,
            () -> jdbc.sql("SELECT * FROM missing_" + table).query(Integer.class).single()))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(count()).isZero();
    }

    private String setting(String name) {
        return jdbc.sql("SELECT current_setting(:name)").param("name", name).query(String.class).single();
    }
    private int count() { return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single(); }
}
