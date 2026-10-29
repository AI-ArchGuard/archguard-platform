package io.github.aiarchguard.platform.audit.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import java.time.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 审计表 JDBC 适配器。普通写入沿用当前连接事务；有界写入显式开启新事务，
 * 在剩余预算内检查锁等待、语句执行及提交前后时效，不向调用方泄露数据库错误详情。
 */
@Repository
public class JdbcAuditRecorder implements AuditRecorder {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PlatformTransactionManager transactions;
    private final DataSource source;

    public JdbcAuditRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper, Clock clock) {
        this(jdbcClient, objectMapper, clock, null, null);
    }

    @Autowired
    public JdbcAuditRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper, Clock clock,
            PlatformTransactionManager transactions, DataSource source) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactions = transactions;
        this.source = source;
    }

    @Override
    public void record(AuditEvent event) {
        insert(jdbcClient, event);
    }

    /** 数据源必须与事务管理器一致；预算耗尽或确认过晚都拒绝后续凭据消费授权。 */
    @Override
    public void record(AuditEvent event, LongSupplier remainingNanos) {
        try {
            if (event == null || source == null || !(transactions instanceof DataSourceTransactionManager manager)
                    || manager.getDataSource() != source) throw unavailable();
            var deadline = new Deadline(remainingNanos);
            var transaction = new TransactionTemplate(transactions);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setTimeout((int) ((deadline.remaining() + 999_999_999L) / 1_000_000_000L));
            // 从同一数据源建立客户端，确保超时参数和 INSERT 使用当前事务的连接域。
            JdbcClient boundedJdbc = JdbcClient.create(source);
            transaction.executeWithoutResult(status -> {
                long remaining = deadline.remaining();
                int statementMs = (int) ((remaining + 999_999L) / 1_000_000L);
                int lockMs = Math.max(1, statementMs / 2);
                boundedJdbc.sql("SELECT set_config('lock_timeout', :lock, true), set_config('statement_timeout', :statement, true)")
                    .param("lock", lockMs + "ms").param("statement", statementMs + "ms")
                    .query((row, index) -> true).single();
                deadline.remaining();
                String metadata = serialize(event);
                deadline.remaining();
                insert(boundedJdbc, event, metadata);
                deadline.remaining();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean ignored) { deadline.remaining(); }
                });
            });
            // 提交确认晚于预算时，也不能据此授权后续读取凭据；这不等于撤销已提交的记录。
            deadline.remaining();
        } catch (RuntimeException failure) { throw unavailable(); }
    }

    private void insert(JdbcClient jdbc, AuditEvent event) {
        insert(jdbc, event, serialize(event));
    }

    private void insert(JdbcClient jdbc, AuditEvent event, String metadata) {
        jdbc.sql("""
                INSERT INTO audit.audit_records
                    (id, actor_id, project_id, action, result, trace_id, metadata, occurred_at)
                VALUES
                    (:id, :actorId, :projectId, :action, :result, :traceId, CAST(:metadata AS jsonb), :occurredAt)
                """)
            .param("id", UUID.randomUUID())
            .param("actorId", event.actorId())
            .param("projectId", event.projectId())
            .param("action", event.action())
            .param("result", event.result().name())
            .param("traceId", event.traceId())
            .param("metadata", metadata)
            .param("occurredAt", Timestamp.from(Instant.now(clock)))
            .update();
    }

    private String serialize(AuditEvent event) {
        try {
            return objectMapper.writeValueAsString(event.metadata());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit metadata cannot be serialized", exception);
        }
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("Bounded audit unavailable"); }

    /** 同时按单调时钟扣减初始预算；调用方返回恒定或递增值也不能给本次写入续期。 */
    private static final class Deadline {
        private static final long MAX_NANOS = Duration.ofSeconds(30).toNanos();
        private final long started = System.nanoTime();
        private final LongSupplier supplier;
        private final long initial;
        Deadline(LongSupplier supplier) { this.supplier = supplier; initial = supplied(); remaining(); }
        long remaining() {
            long external = supplied(), elapsed = System.nanoTime() - started;
            if (elapsed < 0 || elapsed >= initial) throw unavailable();
            return Math.min(external, initial - elapsed);
        }
        private long supplied() {
            if (supplier == null || Thread.currentThread().isInterrupted()) throw unavailable();
            long remaining = supplier.getAsLong();
            if (Thread.currentThread().isInterrupted() || remaining <= 0 || remaining > MAX_NANOS) throw unavailable();
            return remaining;
        }
    }
}
