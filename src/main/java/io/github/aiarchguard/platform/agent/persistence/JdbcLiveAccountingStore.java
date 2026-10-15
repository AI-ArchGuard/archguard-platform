package io.github.aiarchguard.platform.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.LiveBatchView;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingStore;
import io.github.aiarchguard.platform.agent.internal.LiveManifest;
import io.github.aiarchguard.platform.agent.internal.SyntheticBatchInventory;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class JdbcLiveAccountingStore implements LiveAccountingStore {
    private static final String CONTEXT_POLICY = LiveCostPolicy.CONTEXT_VERSION;
    private static final Set<String> STOP_CODES = Set.of("MODEL_DISABLED", "MODEL_UNAVAILABLE", "MODEL_TIMEOUT",
        "AUTHORIZATION_REVOKED", "QUOTA_EXHAUSTED", "OUTPUT_INVALID", "CITATION_INVALID", "INTERNAL_ERROR", "INPUT_TOKEN_LIMIT");
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    JdbcLiveAccountingStore(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @Override public void insert(Batch value) {
        var view = value.view();
        jdbc.sql("""
            INSERT INTO agent.live_batches(id,project_id,enablement_id,deployment_id,inventory_id,approved_by,approved_at,
                expires_at,manifest_sha256,manifest,max_requests,max_cost_microusd,inventory_proof,cost_policy_version)
            VALUES (:id,:p,:e,:d,:inventory,:actor,:at,:expires,:sha,CAST(:manifest AS jsonb),:count,:cost,CAST(:proof AS jsonb),:policy)
            """).param("id", view.id()).param("p", view.projectId()).param("e", view.enablementId()).param("d", value.deploymentId())
            .param("inventory", value.inventoryId()).param("actor", view.approvedBy()).param("at", Timestamp.from(view.approvedAt()))
            .param("expires", Timestamp.from(view.expiresAt())).param("sha", view.manifestSha256()).param("manifest", json(value.manifest()))
            .param("count", view.maxRequests()).param("cost", view.maxCostMicrousd())
            .param("proof", value.inventoryProof() == null ? null : json(value.inventoryProof()))
            .param("policy", value.costPolicyVersion()).update();
        jdbc.sql("INSERT INTO agent.live_batch_usage(batch_id) VALUES (:id)").param("id", view.id()).update();
    }
    @Override public Optional<Batch> batch(UUID project, UUID id) {
        return jdbc.sql("""
            SELECT b.*,r.batch_id IS NOT NULL AS revoked FROM agent.live_batches b
            LEFT JOIN agent.live_batch_revocations r ON r.batch_id=b.id WHERE b.project_id=:p AND b.id=:id
            """).param("p", project).param("id", id).query((rs, row) -> {
                LiveManifest manifest;
                SyntheticBatchInventory.Proof proof;
                try {
                    manifest = mapper.readValue(rs.getString("manifest"), LiveManifest.class);
                    String raw = rs.getString("inventory_proof");
                    proof = raw == null ? null : mapper.readValue(raw, SyntheticBatchInventory.Proof.class);
                }
                catch (com.fasterxml.jackson.core.JsonProcessingException unavailable) { throw new AgentUnavailableException(); }
                return new Batch(new LiveBatchView(id, project, rs.getObject("enablement_id", UUID.class), rs.getObject("approved_by", UUID.class),
                    rs.getTimestamp("approved_at").toInstant(), rs.getString("manifest_sha256"), rs.getTimestamp("expires_at").toInstant(),
                    rs.getInt("max_requests"), rs.getLong("max_cost_microusd"), rs.getBoolean("revoked")), rs.getObject("deployment_id", UUID.class),
                    rs.getObject("inventory_id", UUID.class), manifest, proof, rs.getString("cost_policy_version"));
            }).optional();
    }
    @Override public Usage lockBatch(UUID id) {
        return jdbc.sql("SELECT reserved_microusd,spent_microusd,attempts FROM agent.live_batch_usage WHERE batch_id=:id FOR UPDATE")
            .param("id", id).query((rs, row) -> new Usage(rs.getLong(1), rs.getLong(2), rs.getInt(3))).single();
    }
    @Override public boolean revoke(UUID id, UUID actor, Instant at) {
        return jdbc.sql("INSERT INTO agent.live_batch_revocations(batch_id,revoked_by,revoked_at) VALUES (:id,:actor,:at) ON CONFLICT DO NOTHING")
            .param("id", id).param("actor", actor).param("at", Timestamp.from(at)).update() == 1;
    }
    @Override public Usage lockBudget(String scope, UUID id, LocalDate day) {
        jdbc.sql("INSERT INTO agent.live_budget_usage(scope,scope_id,utc_day) VALUES (:s,:id,:day) ON CONFLICT DO NOTHING")
            .param("s", scope).param("id", id).param("day", day).update();
        return jdbc.sql("SELECT reserved_microusd,spent_microusd FROM agent.live_budget_usage WHERE scope=:s AND scope_id=:id AND utc_day=:day FOR UPDATE")
            .param("s", scope).param("id", id).param("day", day).query((rs, row) -> new Usage(rs.getLong(1), rs.getLong(2), 0)).single();
    }
    @Override public void reserveBudget(String scope, UUID id, LocalDate day, long value) {
        changed(jdbc.sql("UPDATE agent.live_budget_usage SET reserved_microusd=reserved_microusd+:n WHERE scope=:s AND scope_id=:id AND utc_day=:day")
            .param("n", value).param("s", scope).param("id", id).param("day", day).update());
    }
    @Override public void reserveBatch(UUID id, long value) {
        changed(jdbc.sql("UPDATE agent.live_batch_usage SET reserved_microusd=reserved_microusd+:n,attempts=attempts+1 WHERE batch_id=:id")
            .param("n", value).param("id", id).update());
    }
    @Override public Optional<Attempt> attempt(UUID project, UUID request) {
        return jdbc.sql("""
            SELECT a.*,o.state,o.actual_microusd FROM agent.live_attempts a
            LEFT JOIN agent.live_outcomes o ON o.request_id=a.request_id WHERE a.project_id=:p AND a.request_id=:id
            """).param("p", project).param("id", request).query((rs, row) -> new Attempt(request, project, rs.getObject("batch_id", UUID.class),
                rs.getObject("template_id", UUID.class), rs.getObject("attempt_id", UUID.class), rs.getObject("deployment_id", UUID.class),
                rs.getObject("utc_day", LocalDate.class), rs.getString("price_version"), rs.getLong("reserved_microusd"), rs.getString("state"),
                (Long) rs.getObject("actual_microusd"))).optional();
    }
    @Override public ReservationBinding reservationBinding(UUID project, UUID request) {
        return jdbc.sql("SELECT enablement_id,credential_version,settings_revision FROM agent.live_attempts WHERE project_id=:p AND request_id=:id")
            .param("p", project).param("id", request).query((rs, row) -> new ReservationBinding(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getLong(3))).optional().orElseThrow(AgentUnavailableException::new);
    }
    @Override public void insertAttempt(Attempt value, UUID enablement, UUID credential, long revision, Instant now) {
        jdbc.sql("""
            INSERT INTO agent.live_attempts(request_id,project_id,batch_id,template_id,attempt_id,deployment_id,enablement_id,
                credential_version,settings_revision,utc_day,price_version,reserved_microusd,created_at)
            VALUES (:id,:p,:b,:template,:attempt,:d,:e,:credential,:revision,:day,:price,:cost,:at)
            """).param("id", value.requestId()).param("p", value.projectId()).param("b", value.batchId()).param("template", value.templateId())
            .param("attempt", value.attemptId()).param("d", value.deploymentId()).param("e", enablement).param("credential", credential)
            .param("revision", revision).param("day", value.day()).param("price", value.priceVersion()).param("cost", value.reservation())
            .param("at", Timestamp.from(now)).update();
    }
    @Override public void finish(Attempt value, LiveTokenUsage usage, Long cost, Instant now) {
        jdbc.sql("INSERT INTO agent.live_outcomes(request_id,state,usage,actual_microusd,created_at) VALUES (:id,:state,CAST(:usage AS jsonb),:cost,:at)")
            .param("id", value.requestId()).param("state", cost == null ? "UNKNOWN" : "SETTLED").param("usage", cost == null ? null : json(usage))
            .param("cost", cost).param("at", Timestamp.from(now)).update();
        if (cost == null) return;
        for (String scope : java.util.List.of("DEPLOYMENT", "PROJECT")) {
            changed(jdbc.sql("""
                UPDATE agent.live_budget_usage SET reserved_microusd=reserved_microusd-:reserved,spent_microusd=spent_microusd+:cost
                WHERE scope=:s AND scope_id=:id AND utc_day=:day AND reserved_microusd>=:reserved
                """).param("reserved", value.reservation()).param("cost", cost).param("s", scope)
                .param("id", "DEPLOYMENT".equals(scope) ? value.deploymentId() : value.projectId()).param("day", value.day()).update());
        }
        changed(jdbc.sql("""
            UPDATE agent.live_batch_usage SET reserved_microusd=reserved_microusd-:reserved,spent_microusd=spent_microusd+:cost
            WHERE batch_id=:id AND reserved_microusd>=:reserved
            """).param("reserved", value.reservation()).param("cost", cost).param("id", value.batchId()).update());
    }

    @Override public void requireSerialProgress(Batch batch, UUID requestId) {
        if (batch == null || requestId == null || !CONTEXT_POLICY.equals(batch.costPolicyVersion())
                || !TransactionSynchronizationManager.isActualTransactionActive()) throw new AgentUnavailableException();
        var view = batch.view();
        lockBatch(view.id()); // Same lock as reservation/stop; callers already take settings first.
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM agent.live_batches WHERE project_id=:p AND id=:b AND cost_policy_version=:policy)")
                .param("p", view.projectId()).param("b", view.id()).param("policy", CONTEXT_POLICY).query(Boolean.class).single()) invalidSerial();
        if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM agent.live_batch_stops WHERE project_id=:p AND batch_id=:b)")
                .param("p", view.projectId()).param("b", view.id()).query(Boolean.class).single()) invalidSerial();
        var current = jdbc.sql("""
            SELECT r.purpose,r.state,l.template_id FROM agent.requests r
            JOIN agent.live_request_links l ON l.project_id=r.project_id AND l.request_id=r.id
            WHERE r.project_id=:p AND r.id=:id AND l.batch_id=:b
            """).param("p", view.projectId()).param("id", requestId).param("b", view.id())
            .query((row, index) -> new Progress(row.getObject("template_id", UUID.class), row.getString("purpose"),
                row.getString("state"), null)).optional().orElseThrow(AgentEnablementInvalidStateException::new);
        if (!"RUNNING".equals(current.state())) invalidSerial();
        var earlier = jdbc.sql("""
            SELECT a.template_id,r.purpose,r.state,o.state AS outcome FROM agent.live_attempts a
            JOIN agent.requests r ON r.project_id=a.project_id AND r.id=a.request_id
            LEFT JOIN agent.live_outcomes o ON o.request_id=a.request_id
            WHERE a.project_id=:p AND a.batch_id=:b AND a.request_id<>:id
            ORDER BY a.created_at,a.attempt_id
            """).param("p", view.projectId()).param("b", view.id()).param("id", requestId)
            .query((row, index) -> new Progress(row.getObject("template_id", UUID.class), row.getString("purpose"),
                row.getString("state"), row.getString("outcome"))).list();
        if (earlier.isEmpty()) {
            if (!"FINDING_EXPLANATION".equals(current.purpose())) invalidSerial();
        } else {
            if (earlier.size() != 1) invalidSerial();
            var prior = earlier.getFirst();
            if (!"FINDING_EXPLANATION".equals(prior.purpose()) || !"SUCCEEDED".equals(prior.state())
                    || !"SETTLED".equals(prior.outcome()) || !"PR_SUMMARY".equals(current.purpose())
                    || prior.template().equals(current.template())) invalidSerial();
        }
    }

    @Override public void stop(UUID projectId, UUID batchId, UUID requestId, String code, Instant at) {
        if (projectId == null || batchId == null || requestId == null || at == null || !STOP_CODES.contains(code == null ? "" : code)
                || !TransactionSynchronizationManager.isActualTransactionActive()) throw new AgentUnavailableException();
        var current = batch(projectId, batchId).orElseThrow(AgentEnablementInvalidStateException::new);
        if (!CONTEXT_POLICY.equals(current.costPolicyVersion())) throw new AgentEnablementInvalidStateException();
        lockBatch(batchId);
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM agent.live_request_links WHERE project_id=:p AND batch_id=:b AND request_id=:id)")
                .param("p", projectId).param("b", batchId).param("id", requestId).query(Boolean.class).single()) invalidSerial();
        jdbc.sql("""
            INSERT INTO agent.live_batch_stops(batch_id,project_id,request_id,code,created_at)
            VALUES (:b,:p,:id,:code,:at) ON CONFLICT (batch_id) DO NOTHING
            """).param("b", batchId).param("p", projectId).param("id", requestId).param("code", code)
            .param("at", Timestamp.from(at)).update();
    }

    private static void invalidSerial() { throw new AgentEnablementInvalidStateException(); }
    private record Progress(UUID template, String purpose, String state, String outcome) { }
    private void changed(int count) { if (count != 1) throw new AgentUnavailableException(); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException unavailable) { throw new AgentUnavailableException(); }
    }
}
