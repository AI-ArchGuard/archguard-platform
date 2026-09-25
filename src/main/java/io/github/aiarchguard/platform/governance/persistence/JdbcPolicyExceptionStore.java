package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.PolicyExceptionScopeType;
import io.github.aiarchguard.platform.governance.internal.PolicyExceptionRecord;
import io.github.aiarchguard.platform.governance.internal.PolicyExceptionStore;
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
public class JdbcPolicyExceptionStore implements PolicyExceptionStore {
    private final JdbcClient jdbc;
    public JdbcPolicyExceptionStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public void insert(PolicyExceptionRecord value) {
        jdbc.sql("""
            INSERT INTO governance.policy_exceptions(id,project_id,repository_id,target_branch,rule_set_version_id,
              scope_type,scope_value,reason,effective_at,expires_at,created_by,created_at)
            VALUES (:id,:project,:repository,:branch,:rules,:scopeType,:scopeValue,:reason,:effective,:expires,:actor,:created)
            """).param("id", value.id()).param("project", value.projectId())
            .param("repository", value.repositoryId()).param("branch", value.targetBranch())
            .param("rules", value.ruleSetVersionId()).param("scopeType", value.scopeType().name())
            .param("scopeValue", value.scopeValue()).param("reason", value.reason())
            .param("effective", Timestamp.from(value.effectiveAt())).param("expires", Timestamp.from(value.expiresAt()))
            .param("actor", value.createdBy()).param("created", Timestamp.from(value.createdAt())).update();
    }

    @Override public Optional<PolicyExceptionRecord> find(UUID projectId, UUID repositoryId, UUID exceptionId) {
        return jdbc.sql(select() + " WHERE e.project_id=:project AND e.repository_id=:repository AND e.id=:id")
            .param("project", projectId).param("repository", repositoryId).param("id", exceptionId)
            .query(JdbcPolicyExceptionStore::map).optional();
    }

    @Override public List<PolicyExceptionRecord> list(UUID projectId, UUID repositoryId, String branch, UUID rules) {
        return jdbc.sql(select() + """
             WHERE e.project_id=:project AND e.repository_id=:repository
               AND e.target_branch=:branch AND e.rule_set_version_id=:rules ORDER BY e.created_at,e.id
            """).param("project", projectId).param("repository", repositoryId)
            .param("branch", branch).param("rules", rules).query(JdbcPolicyExceptionStore::map).list();
    }

    @Override public boolean revoke(UUID exceptionId, UUID versionId, String reason, UUID actorId, Instant at) {
        return jdbc.sql("""
            INSERT INTO governance.policy_exception_revocations(exception_id,version_id,reason,revoked_by,revoked_at)
            VALUES (:exception,:version,:reason,:actor,:at) ON CONFLICT (exception_id) DO NOTHING
            """).param("exception", exceptionId).param("version", versionId).param("reason", reason)
            .param("actor", actorId).param("at", Timestamp.from(at)).update() == 1;
    }

    private static String select() {
        return """
            SELECT e.*,r.version_id AS revocation_version_id,r.reason AS revocation_reason,
              r.revoked_by,r.revoked_at
            FROM governance.policy_exceptions e
            LEFT JOIN governance.policy_exception_revocations r ON r.exception_id=e.id
            """;
    }
    private static PolicyExceptionRecord map(ResultSet r, int row) throws SQLException {
        return new PolicyExceptionRecord(r.getObject("id", UUID.class), r.getObject("project_id", UUID.class),
            r.getObject("repository_id", UUID.class), r.getString("target_branch"),
            r.getObject("rule_set_version_id", UUID.class), PolicyExceptionScopeType.valueOf(r.getString("scope_type")),
            r.getString("scope_value"), r.getString("reason"), r.getTimestamp("effective_at").toInstant(),
            r.getTimestamp("expires_at").toInstant(), r.getObject("created_by", UUID.class),
            r.getTimestamp("created_at").toInstant(), r.getObject("revoked_by", UUID.class),
            instant(r, "revoked_at"), r.getString("revocation_reason"),
            r.getObject("revocation_version_id", UUID.class));
    }
    private static Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp value = r.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
