package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GithubWebhookDisposition;
import io.github.aiarchguard.platform.governance.internal.GithubWebhookStore;
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
 * 已验签 GitHub 投递、PR 当前 head 及实际应用 head 历史的 JDBC 存储适配器。
 * 验签、时间窗与事件解析在上层；本类依赖唯一键和事件时间条件保存顺序事实。
 */
@Repository
public class JdbcGithubWebhookStore implements GithubWebhookStore {
    private final JdbcClient jdbc;
    public JdbcGithubWebhookStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 按全局 delivery ID 查正文摘要与处理结论，供上层核对同 ID 重放或内容碰撞。 */
    @Override public Optional<Delivery> delivery(UUID id) {
        return jdbc.sql("SELECT payload_sha256,disposition FROM governance.github_webhook_deliveries WHERE delivery_id=:id")
            .param("id", id).query((r, row) -> new Delivery(r.getString("payload_sha256"),
                GithubWebhookDisposition.valueOf(r.getString("disposition")))).optional();
    }
    /**
     * 首次插入 RECEIVED 投递；唯一 delivery ID 的冲突返回 false。
     * 是否接受事件由上层在验签之后决定，此行本身不表示 PR head 已应用。
     */
    @Override public boolean insertDelivery(UUID id, String digest, String event, String action,
            String external, UUID projectId, UUID repositoryId, Instant eventAt, Instant processedAt) {
        return jdbc.sql("""
            INSERT INTO governance.github_webhook_deliveries(delivery_id,payload_sha256,event_type,action,
              provider_repository_id,project_id,repository_id,event_at,processed_at,disposition)
            VALUES (:id,:digest,:event,:action,:external,:project,:repository,:eventAt,:processed,'RECEIVED')
            ON CONFLICT (delivery_id) DO NOTHING
            """).param("id", id).param("digest", digest).param("event", event).param("action", action)
            .param("external", external).param("project", projectId).param("repository", repositoryId)
            .param("eventAt", eventAt == null ? null : Timestamp.from(eventAt))
            .param("processed", Timestamp.from(processedAt)).update() == 1;
    }
    /** 仅将未封存 RECEIVED 投递转为最终结论并封存，保留原投递事实。 */
    @Override public void finishDelivery(UUID id, GithubWebhookDisposition disposition) {
        jdbc.sql("""
            UPDATE governance.github_webhook_deliveries SET disposition=:disposition,sealed=TRUE
            WHERE delivery_id=:id AND disposition='RECEIVED' AND NOT sealed
            """).param("disposition", disposition.name()).param("id", id).update();
    }
    /**
     * 单条 UPSERT 只接受比当前 event_at 严格更新的事件；同时间或旧事件不能推进 head。
     * head 改变时清空当前门禁；不能从事件时间推导 Git ancestry。
     */
    @Override public boolean applyPullRequest(GithubPullRequestView value, UUID deliveryId, Instant processedAt) {
        return jdbc.sql("""
            INSERT INTO governance.github_pull_request_heads AS current(project_id,repository_id,external_id,head_sha,
              base_sha,target_branch,event_at,last_delivery_id,updated_at)
            VALUES (:project,:repository,:pr,:head,:base,:branch,:eventAt,:delivery,:processed)
            ON CONFLICT (project_id,repository_id,external_id) DO UPDATE SET
              head_sha=EXCLUDED.head_sha,base_sha=EXCLUDED.base_sha,target_branch=EXCLUDED.target_branch,
              event_at=EXCLUDED.event_at,last_delivery_id=EXCLUDED.last_delivery_id,
              current_gate_id=CASE WHEN current.head_sha=EXCLUDED.head_sha
                THEN current.current_gate_id ELSE NULL END,
              updated_at=EXCLUDED.updated_at
            WHERE EXCLUDED.event_at>current.event_at
            """).param("project", value.projectId()).param("repository", value.repositoryId())
            .param("pr", value.externalId()).param("head", value.headSha())
            .param("base", value.baseSha()).param("branch", value.targetBranch())
            .param("eventAt", Timestamp.from(value.eventAt())).param("delivery", deliveryId)
            .param("processed", Timestamp.from(processedAt)).update() == 1;
    }
    /**
     * 仅由上层在 applyPullRequest 成功后调用，追加实际 APPLIED 的 head 事实。
     * 与当前 head 更新同处上层 Webhook 事务，V7 不回填旧事件的未知历史。
     */
    @Override public void recordAppliedHead(GithubPullRequestView value, UUID deliveryId) {
        jdbc.sql("""
            INSERT INTO governance.github_pr_head_revisions
              (delivery_id,project_id,repository_id,external_id,head_sha,target_branch,event_at)
            VALUES (:delivery,:project,:repository,:pr,:head,:branch,:eventAt)
            """).param("delivery", deliveryId).param("project", value.projectId())
            .param("repository", value.repositoryId()).param("pr", value.externalId())
            .param("head", value.headSha()).param("branch", value.targetBranch())
            .param("eventAt", Timestamp.from(value.eventAt())).update();
    }
    /** 按已应用事件时间倒序找当前 head 前最近的不同 head；缺少可信历史就返回空。 */
    @Override public Optional<String> previousDistinctHead(UUID projectId, UUID repositoryId,
            String externalId, String targetBranch, String currentHeadSha, Instant currentEventAt) {
        return jdbc.sql("""
            SELECT head_sha FROM governance.github_pr_head_revisions
            WHERE project_id=:project AND repository_id=:repository AND external_id=:pr
              AND target_branch=:branch AND head_sha<>:head AND event_at<:eventAt
            ORDER BY event_at DESC,delivery_id DESC LIMIT 1
            """).param("project", projectId).param("repository", repositoryId)
            .param("pr", externalId).param("branch", targetBranch).param("head", currentHeadSha)
            .param("eventAt", Timestamp.from(currentEventAt)).query(String.class).optional();
    }
    /** 读取限定项目和仓库的当前 PR，只有匹配 V7 历史行才带出修订 ID。 */
    @Override public Optional<GithubPullRequestView> pullRequest(UUID projectId, UUID repositoryId, String externalId) {
        return jdbc.sql("""
            SELECT h.*,r.delivery_id AS current_head_revision_id
            FROM governance.github_pull_request_heads h
            LEFT JOIN governance.github_pr_head_revisions r ON r.delivery_id=h.last_delivery_id
              AND r.project_id=h.project_id AND r.repository_id=h.repository_id
              AND r.external_id=h.external_id AND r.head_sha=h.head_sha
              AND r.target_branch=h.target_branch AND r.event_at=h.event_at
            WHERE h.project_id=:project AND h.repository_id=:repository AND h.external_id=:pr
            """).param("project", projectId).param("repository", repositoryId).param("pr", externalId)
            .query(JdbcGithubWebhookStore::map).optional();
    }
    /** 在项目仓库内按最近处理时间及 PR 编号降序分页展示当前 head。 */
    @Override public List<GithubPullRequestView> listPullRequests(UUID projectId, UUID repositoryId,
            long offset, int limit) {
        return jdbc.sql("""
            SELECT h.*,r.delivery_id AS current_head_revision_id
            FROM governance.github_pull_request_heads h
            LEFT JOIN governance.github_pr_head_revisions r ON r.delivery_id=h.last_delivery_id
              AND r.project_id=h.project_id AND r.repository_id=h.repository_id
              AND r.external_id=h.external_id AND r.head_sha=h.head_sha
              AND r.target_branch=h.target_branch AND r.event_at=h.event_at
            WHERE h.project_id=:project AND h.repository_id=:repository
            ORDER BY h.updated_at DESC,h.external_id DESC LIMIT :limit OFFSET :offset
            """).param("project", projectId).param("repository", repositoryId)
            .param("limit", limit).param("offset", offset)
            .query(JdbcGithubWebhookStore::map).list();
    }
    /**
     * 单条条件更新仅为仍匹配该 head 的 PR 关联门禁，并防止求值时间倒退。
     * 返回 false 可能是 head 已推进或现有门禁更新；不代表外部 GitHub 状态发布结果。
     */
    @Override public boolean attachGateIfCurrent(UUID projectId, UUID repositoryId, String externalId,
            String headSha, UUID gateId) {
        return jdbc.sql("""
            UPDATE governance.github_pull_request_heads SET current_gate_id=:gate
            WHERE project_id=:project AND repository_id=:repository AND external_id=:pr AND head_sha=:head
              AND (current_gate_id IS NULL OR
                (SELECT evaluated_at FROM governance.gate_evaluations WHERE id=current_gate_id)
                <= (SELECT evaluated_at FROM governance.gate_evaluations WHERE id=:gate))
            """).param("gate", gateId).param("project", projectId).param("repository", repositoryId)
            .param("pr", externalId).param("head", headSha).update() == 1;
    }
    private static GithubPullRequestView map(ResultSet r, int row) throws SQLException {
        return new GithubPullRequestView(r.getObject("project_id", UUID.class),
            r.getObject("repository_id", UUID.class), r.getString("external_id"),
            r.getString("head_sha"), r.getString("base_sha"), r.getString("target_branch"),
            r.getTimestamp("event_at").toInstant(), r.getObject("current_gate_id", UUID.class),
            r.getObject("current_head_revision_id", UUID.class));
    }
}
