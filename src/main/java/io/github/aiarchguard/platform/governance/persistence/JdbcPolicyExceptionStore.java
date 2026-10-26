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

/**
 * 有期限策略例外及撤销历史的 JDBC 存储适配器。
 * 是否在求值时刻有效由上层策略按生效、到期和撤销时间判断，这里不改写原例外。
 */
@Repository
public class JdbcPolicyExceptionStore implements PolicyExceptionStore {
    private final JdbcClient jdbc;
    public JdbcPolicyExceptionStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 按项目、仓库、分支、规则集版本及规则或指纹范围写入新例外事实。 */
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

    /** 按项目和仓库限定例外 ID，并连同可能存在的撤销版本读取。 */
    @Override public Optional<PolicyExceptionRecord> find(UUID projectId, UUID repositoryId, UUID exceptionId) {
        return jdbc.sql(select() + " WHERE e.project_id=:project AND e.repository_id=:repository AND e.id=:id")
            .param("project", projectId).param("repository", repositoryId).param("id", exceptionId)
            .query(JdbcPolicyExceptionStore::map).optional();
    }

    /** 返回范围内全部历史例外，包括未生效、已到期和已撤销项，按创建时间及 ID 升序。 */
    @Override public List<PolicyExceptionRecord> list(UUID projectId, UUID repositoryId, String branch, UUID rules) {
        return jdbc.sql(select() + """
             WHERE e.project_id=:project AND e.repository_id=:repository
               AND e.target_branch=:branch AND e.rule_set_version_id=:rules ORDER BY e.created_at,e.id
            """).param("project", projectId).param("repository", repositoryId)
            .param("branch", branch).param("rules", rules).query(JdbcPolicyExceptionStore::map).list();
    }

    /** 追加撤销版本；唯一 exception_id 使重复撤销返回 false，原例外行不变。 */
    @Override public boolean revoke(UUID exceptionId, UUID versionId, String reason, UUID actorId, Instant at) {
        return jdbc.sql("""
            INSERT INTO governance.policy_exception_revocations(exception_id,version_id,reason,revoked_by,revoked_at)
            VALUES (:exception,:version,:reason,:actor,:at) ON CONFLICT (exception_id) DO NOTHING
            """).param("exception", exceptionId).param("version", versionId).param("reason", reason)
            .param("actor", actorId).param("at", Timestamp.from(at)).update() == 1;
    }

    /** 左连接撤销事实，使无撤销记录的例外也保留在结果中。 */
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
