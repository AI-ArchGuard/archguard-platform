package io.github.aiarchguard.platform.agent.persistence;

import io.github.aiarchguard.platform.agent.AgentConflictException;
import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentQuotaExceededException;
import io.github.aiarchguard.platform.agent.AgentSettingsPreconditionException;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.internal.AgentOutputValidator;
import io.github.aiarchguard.platform.agent.internal.LiveDatabaseWork;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import io.github.aiarchguard.platform.identity.InactiveAuthenticatedTaskException;
import io.github.aiarchguard.platform.identity.InvalidCurrentActorException;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 将原执行剩余期限应用到独立 PostgreSQL 事务，连接取得后设置本事务锁/语句超时并复核提交前期限；
 * 不修改共享池，池获取与提交回执仍有独立限制。
 */
@Component
public final class JdbcLiveDatabaseWork implements LiveDatabaseWork {
    private static final long MAX_NANOS = Duration.ofSeconds(30).toNanos();
    private static final long CLEANUP_NANOS = Duration.ofSeconds(5).toNanos();
    private final PlatformTransactionManager transactions;
    private final DataSource source;
    private final JdbcClient jdbc;

    public JdbcLiveDatabaseWork(PlatformTransactionManager transactions, DataSource source) {
        this.transactions = transactions; this.source = source; jdbc = JdbcClient.create(source);
    }

    /**
     * 验证事务管理器绑定相同 DataSource，再用 REQUIRES_NEW 设置本事务 PostgreSQL 超时；
     * 连接迟到时先验剩余期限，过期不执行 action。
     */
    @Override public <T> T transaction(LongSupplier remainingNanos, boolean readOnly, Supplier<T> action) {
        try {
            if (action == null || !(transactions instanceof DataSourceTransactionManager manager)
                    || manager.getDataSource() != source) throw new AgentUnavailableException();
            var deadline = new Deadline(remainingNanos);
            var transaction = new TransactionTemplate(transactions);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setReadOnly(readOnly);
            transaction.setTimeout((int) ((deadline.remaining() + 999_999_999L) / 1_000_000_000L));
            // Spring obtains a connection before invoking this callback. Never mutate the shared pool's
            // connectionTimeout: acquisition may outlast this execution budget, but then runs zero action.
            return transaction.execute(status -> {
                try {
                    long remaining = deadline.remaining();
                    int statementMs = (int) ((remaining + 999_999L) / 1_000_000L);
                    int lockMs = Math.max(1, statementMs / 2);
                    jdbc.sql("SELECT set_config('lock_timeout', :lock, true), set_config('statement_timeout', :statement, true)")
                        .param("lock", lockMs + "ms").param("statement", statementMs + "ms")
                        .query((row, index) -> true).single();
                    deadline.remaining();
                    T value = action.get();
                    deadline.remaining();
                    // Register after the action so its existing result/audit synchronizations run first.
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void beforeCommit(boolean ignored) { deadline.remaining(); }
                    });
                    return value;
                } catch (RuntimeException failure) { throw safe(failure); }
            });
        } catch (RuntimeException failure) { throw safe(failure); }
    }

    /** 清理以独立五秒预算尝试保存费用/失败；暂清中断仅用于收尾，最后恢复原中断及清理中新中断，不给发送恢复机会。 */
    @Override public <T> T cleanup(Supplier<T> action) {
        boolean previouslyInterrupted = Thread.interrupted();
        try { return transaction(() -> CLEANUP_NANOS, false, action); }
        finally {
            boolean interruptedDuringCleanup = Thread.interrupted();
            if (previouslyInterrupted || interruptedDuringCleanup) Thread.currentThread().interrupt();
        }
    }

    /** 把超时 SQLState 和已知业务错误重建为无底层 cause 的稳定异常；未知数据库/解析异常统一不可用。 */
    private static RuntimeException safe(RuntimeException failure) {
        if (failure instanceof QueryTimeoutException || failure instanceof TransactionTimedOutException
                || Thread.currentThread().isInterrupted() || timeoutState(failure)) return timeout();
        if (failure instanceof LiveResponsePort.CallFailure call) return new LiveResponsePort.CallFailure(call.code());
        if (failure instanceof AgentQuotaExceededException) return new AgentQuotaExceededException();
        if (failure instanceof AgentConflictException) return new AgentConflictException();
        if (failure instanceof AgentInvalidException) return new AgentInvalidException();
        if (failure instanceof AgentNotFoundException) return new AgentNotFoundException();
        if (failure instanceof AgentEnablementInvalidStateException) return new AgentEnablementInvalidStateException();
        if (failure instanceof AgentSettingsPreconditionException precondition) return new AgentSettingsPreconditionException(precondition.missing());
        if (failure instanceof ProjectPermissionDeniedException) return new ProjectPermissionDeniedException();
        if (failure instanceof ProjectNotFoundException) return new ProjectNotFoundException();
        if (failure instanceof InactiveAuthenticatedTaskException) return new InactiveAuthenticatedTaskException();
        if (failure instanceof InvalidCurrentActorException) return new InvalidCurrentActorException();
        if (failure instanceof AgentOutputValidator.InvalidOutput output) {
            return new AgentOutputValidator.InvalidOutput(Set.of("OUTPUT_INVALID", "CITATION_INVALID").contains(output.code() == null ? "" : output.code())
                ? output.code() : "OUTPUT_INVALID");
        }
        if (failure instanceof AgentUnavailableException) return new AgentUnavailableException();
        return new AgentUnavailableException();
    }

    private static boolean timeoutState(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof SQLException sql && Set.of("57014", "55P03").contains(sql.getSQLState() == null ? "" : sql.getSQLState())) return true;
        }
        return false;
    }

    private static LiveResponsePort.CallFailure timeout() { return new LiveResponsePort.CallFailure("MODEL_TIMEOUT"); }

    private static final class Deadline {
        private final long started = System.nanoTime();
        private final LongSupplier supplier;
        private final long initial;
        Deadline(LongSupplier supplier) { this.supplier = supplier; initial = supplied(); remaining(); }
        long remaining() {
            long external = supplied(), elapsed = System.nanoTime() - started;
            if (elapsed < 0 || elapsed >= initial) throw timeout();
            return Math.min(external, initial - elapsed);
        }
        private long supplied() {
            if (Thread.currentThread().isInterrupted()) throw timeout();
            if (supplier == null) throw new AgentUnavailableException();
            long remaining;
            try { remaining = supplier.getAsLong(); }
            catch (RuntimeException invalid) { throw new AgentUnavailableException(); }
            if (Thread.currentThread().isInterrupted() || remaining <= 0) throw timeout();
            if (remaining > MAX_NANOS) throw new AgentUnavailableException();
            return remaining;
        }
    }
}
