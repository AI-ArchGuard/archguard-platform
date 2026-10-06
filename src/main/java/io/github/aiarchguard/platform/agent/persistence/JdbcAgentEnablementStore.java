package io.github.aiarchguard.platform.agent.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.PersonalEnablementInput;
import io.github.aiarchguard.platform.agent.internal.AgentEnablementStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAgentEnablementStore implements AgentEnablementStore {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    JdbcAgentEnablementStore(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @Override public Settings settings(UUID project) {
        return read(project, "").orElse(new Settings(false, 0, null));
    }
    @Override public Settings lockSettings(UUID project) {
        jdbc.sql("INSERT INTO agent.project_settings(project_id) VALUES (:p) ON CONFLICT DO NOTHING").param("p", project).update();
        return read(project, " FOR UPDATE").orElseThrow(AgentUnavailableException::new);
    }
    private Optional<Settings> read(UUID project, String lock) {
        return jdbc.sql("SELECT enabled,revision,enablement_id FROM agent.project_settings WHERE project_id=:p" + lock)
            .param("p", project).query((rs, row) -> new Settings(rs.getBoolean("enabled"), rs.getLong("revision"),
                rs.getObject("enablement_id", UUID.class))).optional();
    }
    @Override public void update(UUID project, boolean enabled, UUID id) {
        if (jdbc.sql("UPDATE agent.project_settings SET enabled=:e,enablement_id=:id,revision=revision+1 WHERE project_id=:p")
            .param("e", enabled).param("id", id).param("p", project).update() != 1) throw new AgentUnavailableException();
    }
    @Override public void insert(Approval value) {
        String json;
        try { json = mapper.writeValueAsString(value.acknowledgement()); }
        catch (JsonProcessingException failure) { throw new AgentUnavailableException(); }
        jdbc.sql("""
            INSERT INTO agent.personal_enablements(id,project_id,deployment_id,approved_by,approved_at,expires_at,credential_version,acknowledgement)
            VALUES (:id,:p,:d,:actor,:now,:expiry,:credential,CAST(:json AS jsonb))
            """).param("id", value.id()).param("p", value.projectId()).param("d", value.deploymentId())
            .param("actor", value.approvedBy()).param("now", Timestamp.from(value.approvedAt()))
            .param("expiry", Timestamp.from(value.acknowledgement().expiresAt()))
            .param("credential", value.acknowledgement().credentialVersion()).param("json", json).update();
    }
    @Override public Optional<Approval> find(UUID project, UUID id) {
        return jdbc.sql("""
            SELECT e.*,r.revoked_at FROM agent.personal_enablements e
            LEFT JOIN agent.enablement_revocations r ON r.enablement_id=e.id WHERE e.project_id=:p AND e.id=:id
            """).param("p", project).param("id", id).query((rs, row) -> {
                PersonalEnablementInput acknowledgement;
                try { acknowledgement = mapper.readValue(rs.getString("acknowledgement"), PersonalEnablementInput.class); }
                catch (JsonProcessingException failure) { throw new AgentUnavailableException(); }
                return new Approval(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                    rs.getObject("deployment_id", UUID.class), rs.getObject("approved_by", UUID.class),
                    rs.getTimestamp("approved_at").toInstant(), acknowledgement,
                    rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant());
            }).optional();
    }
    @Override public boolean revoke(UUID id, UUID actor, Instant at) {
        return jdbc.sql("INSERT INTO agent.enablement_revocations(enablement_id,revoked_by,revoked_at) VALUES (:id,:actor,:at) ON CONFLICT DO NOTHING")
            .param("id", id).param("actor", actor).param("at", Timestamp.from(at)).update() == 1;
    }
    @Override public boolean hasHistory(UUID project) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM agent.personal_enablements WHERE project_id=:p)")
            .param("p", project).query(Boolean.class).single();
    }
}
