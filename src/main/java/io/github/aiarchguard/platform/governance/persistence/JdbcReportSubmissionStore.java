package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.GitRevision;
import io.github.aiarchguard.platform.governance.PullRequestRef;
import io.github.aiarchguard.platform.governance.ReportSubmissionStatus;
import io.github.aiarchguard.platform.governance.ReportSubmissionView;
import io.github.aiarchguard.platform.governance.internal.ReportSubmissionStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CI 报告提交的 JDBC 记录，保存请求摘要、扫描任务及最终门禁关联。
 * 接收与完成分属上层两个事务；本类的单条幂等 SQL 不意味着整个流程恰好执行一次。
 */
@Repository
public class JdbcReportSubmissionStore implements ReportSubmissionStore {
    private final JdbcClient jdbc;
    public JdbcReportSubmissionStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 在项目内按幂等键查原提交，供上层核对请求摘要并继续未完成处理。 */
    @Override public Optional<Stored> byKey(UUID projectId, String key) {
        return jdbc.sql("SELECT * FROM governance.report_submissions WHERE project_id=:project AND idempotency_key=:key")
            .param("project", projectId).param("key", key).query(JdbcReportSubmissionStore::map).optional();
    }
    /** 同项目即使换键，只要请求摘要相同也可复用原提交。 */
    @Override public Optional<Stored> byDigest(UUID projectId, String digest) {
        return jdbc.sql("SELECT * FROM governance.report_submissions WHERE project_id=:project AND request_digest=:digest")
            .param("project", projectId).param("digest", digest).query(JdbcReportSubmissionStore::map).optional();
    }
    /** 按项目、仓库和提交 ID 限定查询；成员授权由入口应用服务执行。 */
    @Override public Optional<Stored> find(UUID projectId, UUID repositoryId, UUID id) {
        return jdbc.sql("SELECT * FROM governance.report_submissions WHERE project_id=:project AND repository_id=:repository AND id=:id")
            .param("project", projectId).param("repository", repositoryId).param("id", id)
            .query(JdbcReportSubmissionStore::map).optional();
    }
    /** 从已完成提交中选该 PR/head 最新求值门禁，供后到的 Webhook 尝试关联当前 head。 */
    @Override public Optional<UUID> latestCompletedGateForPullRequest(UUID projectId, UUID repositoryId,
            String externalId, String headSha) {
        return jdbc.sql("""
            SELECT s.gate_evaluation_id FROM governance.report_submissions s
            JOIN governance.gate_evaluations g ON g.id=s.gate_evaluation_id
            WHERE s.project_id=:project AND s.repository_id=:repository AND s.pull_request_external_id=:pr
              AND s.commit_sha=:head AND s.status='COMPLETED'
            ORDER BY g.evaluated_at DESC,g.id DESC LIMIT 1
            """).param("project", projectId).param("repository", repositoryId)
            .param("pr", externalId).param("head", headSha).query(UUID.class).optional();
    }
    /**
     * 校验扫描任务和报告摘要确属一个已完成、有关联门禁的已记录 PR head 修订。
     * 此判断供 PR 摘要的修订来源校验使用，不根据提交者断言补造缺失的 head 历史。
     */
    @Override public boolean matchesCompletedPrRevision(UUID projectId, UUID scanJobId,
            String reportSha256, UUID prHeadRevisionId) {
        return jdbc.sql("""
            SELECT EXISTS (
              SELECT 1 FROM governance.report_submissions s
              JOIN governance.github_pr_head_revisions h
                ON h.project_id=s.project_id AND h.repository_id=s.repository_id
               AND h.external_id=s.pull_request_external_id
               AND h.head_sha=s.pull_request_head_sha AND h.target_branch=s.target_branch
              WHERE h.delivery_id=:revision AND s.project_id=:project AND s.scan_job_id=:job
                AND s.report_sha256=:sha AND s.commit_sha=h.head_sha
                AND s.status='COMPLETED' AND s.gate_evaluation_id IS NOT NULL
            )
            """).param("revision", prHeadRevisionId).param("project", projectId)
            .param("job", scanJobId).param("sha", reportSha256).query(Boolean.class).single();
    }
    /**
     * 首次写入 RECEIVED；项目内键和摘要各有唯一约束，冲突返回 false。
     * 上层接收事务同时写已完成扫描及报告事实；此处只负责提交记录的一条插入。
     */
    @Override public boolean insert(ReportSubmissionView value, String key, UUID scanJobId,
            String scannerVersion, String schemaVersion, UUID actorId) {
        GitRevision revision = value.revision(); PullRequestRef pr = value.pullRequest();
        return jdbc.sql("""
            INSERT INTO governance.report_submissions(id,project_id,repository_id,rule_set_version_id,
              provider,provider_repository_id,commit_sha,target_branch,pull_request_external_id,
              pull_request_head_sha,pull_request_base_sha,scanner_version,schema_version,report_sha256,
              request_digest,idempotency_key,scan_job_id,status,created_by,created_at)
            VALUES (:id,:project,:repository,:rules,:provider,:external,:commit,:branch,:pr,:head,:base,
              :scanner,:schema,:sha,:digest,:key,:job,'RECEIVED',:actor,:created)
            ON CONFLICT DO NOTHING
            """).param("id", value.id()).param("project", value.projectId())
            .param("repository", value.repositoryId()).param("rules", value.ruleSetVersionId())
            .param("provider", revision.provider()).param("external", revision.providerRepositoryId())
            .param("commit", revision.commitSha()).param("branch", revision.targetBranch())
            .param("pr", pr == null ? null : pr.externalId()).param("head", pr == null ? null : pr.headSha())
            .param("base", pr == null ? null : pr.baseSha()).param("scanner", scannerVersion)
            .param("schema", schemaVersion).param("sha", value.reportSha256())
            .param("digest", value.requestDigest()).param("key", key).param("job", scanJobId)
            .param("actor", actorId).param("created", Timestamp.from(value.createdAt())).update() == 1;
    }
    /**
     * 仅将原 RECEIVED 记录条件更新为 COMPLETED 并绑定确切门禁；重复完成返回 false。
     * 若接收后求值中断，上层用同键重放继续处理原提交，不新建扫描任务。
     */
    @Override public boolean complete(UUID projectId, UUID repositoryId, UUID id, UUID gateId, java.time.Instant at) {
        return jdbc.sql("""
            UPDATE governance.report_submissions SET status='COMPLETED',gate_evaluation_id=:gate,completed_at=:at
            WHERE id=:id AND project_id=:project AND repository_id=:repository
              AND status='RECEIVED' AND gate_evaluation_id IS NULL
            """).param("gate", gateId).param("at", Timestamp.from(at)).param("id", id)
            .param("project", projectId).param("repository", repositoryId).update() == 1;
    }
    private static Stored map(ResultSet row, int index) throws SQLException {
        String prId = row.getString("pull_request_external_id");
        var revision = new GitRevision(row.getString("provider"), row.getString("provider_repository_id"),
            row.getString("commit_sha"), row.getString("target_branch"));
        var pr = prId == null ? null : new PullRequestRef(prId, row.getString("pull_request_head_sha"),
            row.getString("pull_request_base_sha"));
        var view = new ReportSubmissionView(row.getObject("id", UUID.class),
            row.getObject("project_id", UUID.class), row.getObject("repository_id", UUID.class),
            row.getObject("rule_set_version_id", UUID.class), revision, pr,
            row.getString("report_sha256"), row.getString("request_digest"),
            ReportSubmissionStatus.valueOf(row.getString("status")),
            row.getObject("gate_evaluation_id", UUID.class), row.getTimestamp("created_at").toInstant());
        return new Stored(view, row.getString("idempotency_key"), row.getObject("scan_job_id", UUID.class),
            row.getString("scanner_version"), row.getString("schema_version"));
    }
}
