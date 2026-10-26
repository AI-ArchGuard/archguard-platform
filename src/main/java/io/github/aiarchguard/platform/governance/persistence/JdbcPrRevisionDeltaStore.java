package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.internal.PrRevisionDeltaStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * PR 修订差异所需的候选快照定位器；只查已完成提交关联的门禁与比较。
 * 这里不计算基线门禁，也不从报告到达顺序或 Git ancestry 猜测前一 head。
 */
@Repository
public class JdbcPrRevisionDeltaStore implements PrRevisionDeltaStore {
    private final JdbcClient jdbc;
    public JdbcPrRevisionDeltaStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * 在确切项目、仓库、PR、head、分支和规则集版本内选最新已完成候选。
     * 上层 GovernanceReadService 在 REPEATABLE_READ 只读事务中读取当前 head、前驱及双方快照，
     * 并确认当前候选门禁确实是当前 head 所关联的门禁；无候选时返回空而不假定空集合。
     */
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
