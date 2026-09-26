package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.GateErrorKind;
import io.github.aiarchguard.platform.governance.GateEvaluationView;
import io.github.aiarchguard.platform.governance.GateOutcome;
import io.github.aiarchguard.platform.governance.internal.GateEvaluationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGateEvaluationStore implements GateEvaluationStore {
    private final JdbcClient jdbc;
    public JdbcGateEvaluationStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Stored> byKey(UUID projectId, String key) {
        return jdbc.sql(select() + " WHERE g.project_id=:project AND g.idempotency_key=:key")
            .param("project", projectId).param("key", key)
            .query((row, number) -> new Stored(map(row, number), row.getString("request_sha256"))).optional();
    }
    @Override public Optional<GateEvaluationView> find(UUID projectId, UUID repositoryId, UUID id) {
        return jdbc.sql(select() + " WHERE g.project_id=:project AND g.repository_id=:repository AND g.id=:id")
            .param("project", projectId).param("repository", repositoryId).param("id", id)
            .query(JdbcGateEvaluationStore::map).optional();
    }
    @Override public List<GateEvaluationView> list(UUID projectId, UUID repositoryId, String branch,
            UUID ruleSetVersionId, String pullRequestId, long offset, int limit) {
        String sql = select() + """
            WHERE g.project_id=:project AND g.repository_id=:repository
              AND g.target_branch=:branch AND g.rule_set_version_id=:rules
            """;
        if (pullRequestId != null) {
            sql += """
                AND EXISTS (SELECT 1 FROM governance.report_submissions s
                  WHERE s.gate_evaluation_id=g.id AND s.project_id=g.project_id
                    AND s.repository_id=g.repository_id AND s.pull_request_external_id=:pr)
                """;
        }
        sql += " ORDER BY g.evaluated_at DESC,g.id DESC LIMIT :limit OFFSET :offset";
        var query = jdbc.sql(sql).param("project", projectId).param("repository", repositoryId)
            .param("branch", branch).param("rules", ruleSetVersionId)
            .param("limit", limit).param("offset", offset);
        if (pullRequestId != null) query = query.param("pr", pullRequestId);
        return query.query(JdbcGateEvaluationStore::map).list();
    }
    @Override public boolean insert(GateEvaluationView value, String key, String requestSha256, UUID actorId) {
        int inserted = jdbc.sql("""
            INSERT INTO governance.gate_evaluations(id,project_id,repository_id,target_branch,rule_set_version_id,
              candidate_job_id,comparison_id,baseline_version_id,idempotency_key,request_sha256,outcome,
              ci_exit_code,error_kind,error_code,policy_version,fingerprint_version,new_count,existing_count,
              resolved_count,blocked_count,evaluated_at,created_by)
            VALUES (:id,:project,:repository,:branch,:rules,:job,:comparison,:baseline,:key,:digest,:outcome,
              :exit,:errorKind,:errorCode,:policy,:fingerprint,:newCount,:existingCount,:resolvedCount,
              :blockedCount,:at,:actor)
            ON CONFLICT (project_id,idempotency_key) DO NOTHING
            """).param("id", value.id()).param("project", value.projectId())
            .param("repository", value.repositoryId()).param("branch", value.targetBranch())
            .param("rules", value.ruleSetVersionId()).param("job", value.candidateJobId())
            .param("comparison", value.comparisonId()).param("baseline", value.baselineVersionId())
            .param("key", key).param("digest", requestSha256).param("outcome", value.outcome().name())
            .param("exit", value.ciExitCode())
            .param("errorKind", value.errorKind() == null ? null : value.errorKind().name())
            .param("errorCode", value.errorCode()).param("policy", value.policyVersion())
            .param("fingerprint", value.fingerprintVersion()).param("newCount", value.newCount())
            .param("existingCount", value.existingCount()).param("resolvedCount", value.resolvedCount())
            .param("blockedCount", value.blockedCount()).param("at", Timestamp.from(value.evaluatedAt()))
            .param("actor", actorId).update();
        if (inserted == 0) return false;
        for (UUID exceptionId : value.matchedExceptionVersionIds()) {
            jdbc.sql("""
                INSERT INTO governance.gate_exception_hits(gate_evaluation_id,exception_id,exception_version_id)
                VALUES (:gate,:exception,:version)
                """).param("gate", value.id()).param("exception", exceptionId)
                .param("version", exceptionId).update();
        }
        return true;
    }
    @Override public void seal(UUID id) {
        jdbc.sql("UPDATE governance.gate_evaluations SET sealed=TRUE WHERE id=:id")
            .param("id", id).update();
    }
    private static String select() {
        return """
            SELECT g.*, COALESCE((SELECT array_agg(h.exception_version_id ORDER BY h.exception_version_id)
              FROM governance.gate_exception_hits h WHERE h.gate_evaluation_id=g.id), ARRAY[]::uuid[]) AS hits
            FROM governance.gate_evaluations g
            """;
    }
    private static GateEvaluationView map(ResultSet row, int number) throws SQLException {
        var hits = (UUID[]) row.getArray("hits").getArray();
        String kind = row.getString("error_kind");
        return new GateEvaluationView(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
            row.getObject("repository_id", UUID.class), row.getString("target_branch"),
            row.getObject("rule_set_version_id", UUID.class), row.getObject("candidate_job_id", UUID.class),
            row.getObject("comparison_id", UUID.class), row.getObject("baseline_version_id", UUID.class),
            GateOutcome.valueOf(row.getString("outcome")), row.getInt("ci_exit_code"),
            kind == null ? null : GateErrorKind.valueOf(kind), row.getString("error_code"),
            row.getString("policy_version"), row.getString("fingerprint_version"), row.getLong("new_count"),
            row.getLong("existing_count"), row.getLong("resolved_count"), row.getLong("blocked_count"),
            List.of(hits), row.getTimestamp("evaluated_at").toInstant());
    }
}
