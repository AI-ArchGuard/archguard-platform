package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.internal.PrRevisionDeltaStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPrRevisionDeltaStore implements PrRevisionDeltaStore {
    private final JdbcClient jdbc;
    public JdbcPrRevisionDeltaStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Candidate> candidate(UUID projectId, UUID repositoryId, String externalId,
            String headSha, String targetBranch, UUID ruleSetVersionId) {
        return jdbc.sql("""
            SELECT g.id AS gate_id,c.id AS comparison_id,c.fingerprint_version
            FROM governance.report_submissions s
            JOIN governance.gate_evaluations g ON g.id=s.gate_evaluation_id
            JOIN governance.comparisons c ON c.id=g.comparison_id
            WHERE s.project_id=:project AND s.repository_id=:repository
              AND s.pull_request_external_id=:pr AND s.commit_sha=:head
              AND s.pull_request_head_sha=:head AND s.target_branch=:branch
              AND s.rule_set_version_id=:rules AND s.status='COMPLETED'
            ORDER BY g.evaluated_at DESC,g.id DESC LIMIT 1
            """).param("project", projectId).param("repository", repositoryId)
            .param("pr", externalId).param("head", headSha).param("branch", targetBranch)
            .param("rules", ruleSetVersionId)
            .query((r, row) -> new Candidate(r.getObject("gate_id", UUID.class),
                r.getObject("comparison_id", UUID.class), r.getString("fingerprint_version"))).optional();
    }
}
