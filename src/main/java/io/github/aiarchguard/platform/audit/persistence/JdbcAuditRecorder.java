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

    @Override
    public void record(AuditEvent event, LongSupplier remainingNanos) {
        try {
            if (event == null || source == null || !(transactions instanceof DataSourceTransactionManager manager)
                    || manager.getDataSource() != source) throw unavailable();
            var deadline = new Deadline(remainingNanos);
            var transaction = new TransactionTemplate(transactions);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.setTimeout((int) ((deadline.remaining() + 999_999_999L) / 1_000_000_000L));
            // Use this exact transaction connection domain, not a caller-supplied JdbcClient.
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
            // A late commit acknowledgement cannot authorize the credential callback either.
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

    /** Can only shorten the caller's original budget; constant/increasing suppliers cannot renew it. */
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
