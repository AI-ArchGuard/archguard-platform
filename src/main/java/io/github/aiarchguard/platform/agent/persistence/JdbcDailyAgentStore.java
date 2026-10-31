package io.github.aiarchguard.platform.agent.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import io.github.aiarchguard.platform.agent.internal.DailyAgentStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 日常授权的 JDBC 适配器；仅存取元数据，Task7 才装配生产运行时。 */
public final class JdbcDailyAgentStore implements DailyAgentStore {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public JdbcDailyAgentStore(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    @Override public Settings settings(UUID project) { return settings(project, false).orElse(new Settings(false, 0, null)); }
    /** 不以不存在的行作为无锁状态；先惰性插入，再锁住实际设置行。 */
    @Override public Settings lockSettings(UUID project) {
        jdbc.sql("INSERT INTO agent.daily_project_settings(project_id) VALUES (:p) ON CONFLICT DO NOTHING").param("p", project).update();
        return settings(project, true).orElseThrow(AgentUnavailableException::new);
    }
    private Optional<Settings> settings(UUID project, boolean lock) {
        return jdbc.sql("SELECT enabled,revision,grant_id FROM agent.daily_project_settings WHERE project_id=:p" + (lock ? " FOR UPDATE" : ""))
            .param("p", project).query((rs, row) -> new Settings(rs.getBoolean("enabled"), rs.getLong("revision"), rs.getObject("grant_id", UUID.class))).optional();
    }
    @Override public void updateSettings(UUID project, boolean enabled, UUID grant) {
        if (jdbc.sql("UPDATE agent.daily_project_settings SET enabled=:enabled,grant_id=:g,revision=revision+1 WHERE project_id=:p")
            .param("enabled", enabled).param("g", grant).param("p", project).update() != 1) throw new AgentUnavailableException();
    }
    @Override public Optional<Risk> findRisk(UUID project, UUID id) { return risk(project, "e.id=:id", id, null, null); }
    @Override public Optional<Risk> findRiskByKey(UUID project, UUID owner, String key) { return risk(project, "e.owner_id=:owner AND e.idempotency_key=:key", null, owner, key); }
    private Optional<Risk> risk(UUID project, String condition, UUID id, UUID owner, String key) {
        var statement = jdbc.sql("SELECT e.*,r.revoked_by,r.revoked_at FROM agent.daily_risk_acknowledgements e LEFT JOIN agent.daily_risk_revocations r ON r.risk_acknowledgement_id=e.id WHERE e.project_id=:p AND " + condition).param("p", project);
        if (id != null) statement = statement.param("id", id); else statement = statement.param("owner", owner).param("key", key);
        return statement.query((rs, row) -> {
            try {
                var envelope = mapper.readTree(rs.getString("acknowledgement"));
                var binding = mapper.treeToValue(envelope.get("runtimeBinding"), DailyBindingSnapshot.class);
                var input = new DailyRiskInput(envelope.path("schemaVersion").asText(), envelope.path("runtimeBindingDigest").asText(),
                    Instant.parse(envelope.path("expiresAt").asText()), envelope.path("sourcesReviewed").booleanValue(),
                    envelope.path("riskAccepted").booleanValue(), envelope.path("materialRightsAccepted").booleanValue());
                return new Risk(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getObject("deployment_id", UUID.class),
                    rs.getObject("owner_id", UUID.class), rs.getString("idempotency_key"), rs.getString("input_digest"), rs.getString("runtime_binding_digest"),
                    rs.getObject("credential_version", UUID.class), rs.getString("data_policy_version"), rs.getTimestamp("acknowledged_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(), binding, input, revocation(rs));
            } catch (JsonProcessingException | java.time.DateTimeException invalid) { throw new AgentUnavailableException(); }
        }).optional();
    }
    /** 胜者追加不可变封套；失去幂等键竞争时返回 false，由应用在同事务重读胜者，不捕获已中止事务的唯一键异常。 */
    @Override public boolean insertRisk(Risk value) {
        var in = value.input();
        var envelope = mapper.createObjectNode().put("schemaVersion", in.schemaVersion()).put("runtimeBindingDigest", in.runtimeBindingDigest())
            .put("expiresAt", in.expiresAt().toString()).put("sourcesReviewed", in.sourcesReviewed()).put("riskAccepted", in.riskAccepted())
            .put("materialRightsAccepted", in.materialRightsAccepted());
        envelope.set("runtimeBinding", mapper.valueToTree(value.binding()));
        return jdbc.sql("""
            INSERT INTO agent.daily_risk_acknowledgements(id,project_id,deployment_id,owner_id,idempotency_key,input_digest,runtime_binding_digest,
                credential_version,data_policy_version,acknowledged_at,expires_at,acknowledgement)
            VALUES (:id,:p,:d,:owner,:key,:input,:binding,:v,:policy,:at,:expiry,CAST(:body AS jsonb))
            ON CONFLICT(project_id,owner_id,idempotency_key) DO NOTHING
            """).param("id", value.id()).param("p", value.projectId()).param("d", value.deploymentId()).param("owner", value.ownerId())
            .param("key", value.key()).param("input", value.inputDigest()).param("binding", value.runtimeBindingDigest()).param("v", value.credentialVersion())
            .param("policy", value.dataPolicyVersion()).param("at", Timestamp.from(value.acknowledgedAt())).param("expiry", Timestamp.from(value.expiresAt()))
            .param("body", json(envelope)).update() == 1;
    }
    @Override public boolean revokeRisk(UUID risk, UUID actor, Instant at) {
        return jdbc.sql("INSERT INTO agent.daily_risk_revocations(risk_acknowledgement_id,revoked_by,revoked_at) VALUES (:id,:actor,:at) ON CONFLICT DO NOTHING")
            .param("id", risk).param("actor", actor).param("at", Timestamp.from(at)).update() == 1;
    }
    @Override public Optional<Grant> findGrant(UUID project, UUID id) { return grant(project, "g.id=:id", id, null, null); }
    @Override public Optional<Grant> findGrantByKey(UUID project, UUID owner, String key) { return grant(project, "g.owner_id=:owner AND g.idempotency_key=:key", null, owner, key); }
    private Optional<Grant> grant(UUID project, String condition, UUID id, UUID owner, String key) {
        var statement = jdbc.sql("""
            SELECT g.*,r.revoked_by,r.revoked_at,u.attempts AS usage_attempts,u.reserved_microusd,u.spent_microusd,u.active_request_id
            FROM agent.daily_grants g LEFT JOIN agent.daily_grant_revocations r ON r.grant_id=g.id
            LEFT JOIN agent.daily_grant_usage u ON u.project_id=g.project_id AND u.grant_id=g.id
            WHERE g.project_id=:p AND
            """ + condition).param("p", project);
        if (id != null) statement = statement.param("id", id); else statement = statement.param("owner", owner).param("key", key);
        return statement.query((rs, row) -> {
            if (rs.getObject("usage_attempts") == null) throw new AgentUnavailableException();
            var array = rs.getArray("allowed_purposes");
            try {
                var purposes = Arrays.stream((String[]) array.getArray()).map(DailyPurpose::valueOf).sorted().toList();
                return new Grant(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getObject("deployment_id", UUID.class),
                    rs.getObject("owner_id", UUID.class), rs.getObject("risk_acknowledgement_id", UUID.class), rs.getString("idempotency_key"),
                    rs.getString("input_digest"), rs.getString("runtime_binding_digest"), rs.getObject("credential_version", UUID.class), purposes,
                    rs.getInt("max_attempts"), rs.getLong("max_cost_microusd"), rs.getTimestamp("approved_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(), revocation(rs), usage(rs, "usage_attempts"));
            } catch (IllegalArgumentException invalid) { throw new AgentUnavailableException(); }
            finally { array.free(); }
        }).optional();
    }
    @Override public boolean insertGrant(Grant value) {
        // 用途已在应用校验为固定枚举；字符串仅由枚举名构造，不拼接调用方原文。
        String purposes = value.allowedPurposes().stream().map(p -> "'" + p.name() + "'").collect(java.util.stream.Collectors.joining(",", "ARRAY[", "]::text[]"));
        return jdbc.sql("""
            INSERT INTO agent.daily_grants(id,project_id,deployment_id,owner_id,risk_acknowledgement_id,idempotency_key,input_digest,
                runtime_binding_digest,credential_version,allowed_purposes,max_attempts,max_cost_microusd,approved_at,expires_at)
            VALUES (:id,:p,:d,:owner,:risk,:key,:input,:binding,:v,
            """ + purposes + ",:attempts,:cost,:at,:expiry) ON CONFLICT(project_id,owner_id,idempotency_key) DO NOTHING")
            .param("id", value.id()).param("p", value.projectId()).param("d", value.deploymentId()).param("owner", value.ownerId()).param("risk", value.riskAcknowledgementId())
            .param("key", value.key()).param("input", value.inputDigest()).param("binding", value.runtimeBindingDigest()).param("v", value.credentialVersion())
            .param("attempts", value.maxAttempts()).param("cost", value.maxCostMicrousd()).param("at", Timestamp.from(value.approvedAt())).param("expiry", Timestamp.from(value.expiresAt())).update() == 1;
    }
    @Override public boolean revokeGrant(UUID grant, UUID actor, Instant at) {
        return jdbc.sql("INSERT INTO agent.daily_grant_revocations(grant_id,revoked_by,revoked_at) VALUES (:id,:actor,:at) ON CONFLICT DO NOTHING")
            .param("id", grant).param("actor", actor).param("at", Timestamp.from(at)).update() == 1;
    }
    @Override public Usage lockUsage(UUID project, UUID grant) {
        return jdbc.sql("SELECT attempts,reserved_microusd,spent_microusd,active_request_id FROM agent.daily_grant_usage WHERE project_id=:p AND grant_id=:g FOR UPDATE")
            .param("p", project).param("g", grant).query((rs, row) -> usage(rs, "attempts")).optional().orElseThrow(AgentUnavailableException::new);
    }
    /** 风险撤销锁住所有引用授权的用量；外层已持有设置锁，固定 grant_id 顺序避免反向锁。 */
    @Override public void lockRiskUsage(UUID project, UUID risk) {
        jdbc.sql("SELECT u.grant_id FROM agent.daily_grant_usage u JOIN agent.daily_grants g ON g.project_id=u.project_id AND g.id=u.grant_id WHERE g.project_id=:p AND g.risk_acknowledgement_id=:r ORDER BY u.grant_id FOR UPDATE OF u")
            .param("p", project).param("r", risk).query(UUID.class).list();
    }
    @Override public void initializeUsage(UUID project, UUID grant) {
        if (jdbc.sql("INSERT INTO agent.daily_grant_usage(grant_id,project_id) VALUES (:g,:p)").param("g", grant).param("p", project).update() != 1) throw new AgentUnavailableException();
    }
    private Usage usage(ResultSet rs, String attempts) throws SQLException {
        return new Usage(rs.getInt(attempts), rs.getLong("reserved_microusd"), rs.getLong("spent_microusd"), rs.getObject("active_request_id", UUID.class));
    }
    private Revocation revocation(ResultSet rs) throws SQLException {
        return rs.getTimestamp("revoked_at") == null ? null : new Revocation(rs.getObject("revoked_by", UUID.class), rs.getTimestamp("revoked_at").toInstant());
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (JsonProcessingException invalid) { throw new AgentUnavailableException(); }
    }
}
