package io.github.aiarchguard.platform.agent.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.internal.AgentSnapshot;
import io.github.aiarchguard.platform.agent.internal.AgentStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentStore implements AgentStore {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcAgentStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public boolean insert(AgentSnapshot snapshot, String key) {
        AgentRequestView view = snapshot.view();
        return jdbc.sql("""
            INSERT INTO agent.requests (id, project_id, requester_id, idempotency_key, input_digest,
                trace_id, purpose, state, bindings, model_input, candidates, result, failure, usage,
                created_at, updated_at)
            VALUES (:id, :project, :requester, :key, :digest, :trace, :purpose, :state,
                CAST(:bindings AS jsonb), CAST(:input AS jsonb), CAST(:candidates AS jsonb),
                CAST(:result AS jsonb), CAST(:failure AS jsonb), CAST(:usage AS jsonb), :created, :updated)
            ON CONFLICT (project_id, requester_id, idempotency_key) DO NOTHING
            """).param("id", view.id()).param("project", view.projectId()).param("requester", view.requesterId())
            .param("key", key).param("digest", view.bindings().inputDigest()).param("trace", view.traceId())
            .param("purpose", view.purpose()).param("state", view.state())
            .param("bindings", json(view.bindings())).param("input", json(snapshot.input()))
            .param("candidates", json(snapshot.candidates())).param("result", json(view.result()))
            .param("failure", json(view.failure())).param("usage", json(view.usage()))
            .param("created", Timestamp.from(view.createdAt())).param("updated", Timestamp.from(view.updatedAt()))
            .update() == 1;
    }

    @Override
    public Optional<AgentSnapshot> findByKey(UUID projectId, UUID requesterId, String key) {
        return jdbc.sql("""
            SELECT * FROM agent.requests WHERE project_id=:project AND requester_id=:requester AND idempotency_key=:key
            """).param("project", projectId).param("requester", requesterId).param("key", key)
            .query(this::map).optional();
    }

    @Override
    public Optional<AgentSnapshot> find(UUID projectId, UUID requestId) {
        return jdbc.sql("SELECT * FROM agent.requests WHERE project_id=:project AND id=:id")
            .param("project", projectId).param("id", requestId).query(this::map).optional();
    }

    @Override
    public boolean claim(UUID id, Instant at) {
        return jdbc.sql("UPDATE agent.requests SET state='RUNNING', updated_at=:at WHERE id=:id AND state='QUEUED'")
            .param("id", id).param("at", Timestamp.from(at)).update() == 1;
    }

    @Override
    public void setReservation(UUID id, java.time.LocalDate day, long estimate) {
        int updated = jdbc.sql("""
            UPDATE agent.requests SET budget_day=:day, reserved_microusd=:estimate
            WHERE id=:id AND state='RUNNING' AND budget_day IS NULL
            """).param("id", id).param("day", day).param("estimate", estimate).update();
        if (updated != 1) throw new IllegalStateException("Agent reservation slot was unavailable");
    }

    @Override
    public boolean failQueued(UUID id, AgentRequestView.AgentFailure failure, Instant at) {
        return jdbc.sql("""
            UPDATE agent.requests SET state='FAILED', failure=CAST(:failure AS jsonb), updated_at=:at
            WHERE id=:id AND state='QUEUED'
            """).param("id", id).param("failure", json(failure)).param("at", Timestamp.from(at)).update() == 1;
    }

    @Override
    public boolean failRunning(UUID id, AgentRequestView.AgentFailure failure, Instant at) {
        return jdbc.sql("""
            UPDATE agent.requests SET state='FAILED', failure=CAST(:failure AS jsonb), updated_at=:at
            WHERE id=:id AND state='RUNNING'
            """).param("id", id).param("failure", json(failure)).param("at", Timestamp.from(at)).update() == 1;
    }

    @Override
    public boolean finish(UUID id, AgentRequestView.AgentResult result, AgentRequestView.AgentFailure failure,
            AgentRequestView.AgentUsage usage, Instant at) {
        return jdbc.sql("""
            UPDATE agent.requests SET state=:state, result=CAST(:result AS jsonb),
                failure=CAST(:failure AS jsonb), usage=CAST(:usage AS jsonb), updated_at=:at
            WHERE id=:id AND state='RUNNING'
            """).param("id", id).param("state", result == null ? "FAILED" : "SUCCEEDED")
            .param("result", json(result)).param("failure", json(failure)).param("usage", json(usage))
            .param("at", Timestamp.from(at)).update() == 1;
    }

    @Override
    public boolean hasRequests(UUID projectId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM agent.requests WHERE project_id=:project)")
            .param("project", projectId).query(Boolean.class).single();
    }

    @Override public List<AgentSnapshot> expired(Instant cutoff, int limit) {
        return jdbc.sql("""
            SELECT * FROM agent.requests WHERE (state='QUEUED' AND created_at<:cutoff)
                OR (state='RUNNING' AND updated_at<:cutoff)
            ORDER BY updated_at, id LIMIT :limit
            """).param("cutoff", Timestamp.from(cutoff)).param("limit", limit).query(this::map).list();
    }

    @Override public boolean expire(UUID id, Instant cutoff, AgentRequestView.AgentFailure failure, Instant at) {
        return jdbc.sql("""
            UPDATE agent.requests SET state='FAILED', failure=CAST(:failure AS jsonb), updated_at=:at
            WHERE id=:id AND ((state='QUEUED' AND created_at<:cutoff) OR (state='RUNNING' AND updated_at<:cutoff))
            """).param("id", id).param("cutoff", Timestamp.from(cutoff))
            .param("failure", json(failure)).param("at", Timestamp.from(at)).update() == 1;
    }

    private AgentSnapshot map(ResultSet rs, int row) throws SQLException {
        AgentRequestView view = new AgentRequestView(rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class), rs.getObject("requester_id", UUID.class),
            rs.getString("trace_id"), rs.getString("purpose"), rs.getString("state"),
            read(rs.getString("bindings"), AgentRequestView.VersionBindings.class),
            read(rs.getString("result"), AgentRequestView.AgentResult.class),
            read(rs.getString("failure"), AgentRequestView.AgentFailure.class),
            read(rs.getString("usage"), AgentRequestView.AgentUsage.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        List<AgentSnapshot.Candidate> candidates = readList(rs.getString("candidates"));
        return new AgentSnapshot(view, read(rs.getString("model_input"), AgentModelPort.ModelInput.class), candidates);
    }

    private String json(Object value) {
        if (value == null) return null;
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent JSON encoding failed", exception); }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null) return null;
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent JSON decoding failed", exception); }
    }

    private List<AgentSnapshot.Candidate> readList(String value) {
        if (value == null) return List.of();
        try { return mapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent candidate decoding failed", exception); }
    }
}
