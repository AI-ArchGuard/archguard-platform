package io.github.aiarchguard.platform.audit.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import java.time.Clock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRecorder implements AuditRecorder {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcAuditRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void record(AuditEvent event) {
        jdbcClient.sql("""
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
            .param("metadata", serialize(event))
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
}
