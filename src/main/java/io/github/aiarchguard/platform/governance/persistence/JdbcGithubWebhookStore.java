package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.GithubPullRequestView;
import io.github.aiarchguard.platform.governance.GithubWebhookDisposition;
import io.github.aiarchguard.platform.governance.internal.GithubWebhookStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGithubWebhookStore implements GithubWebhookStore {
    private final JdbcClient jdbc;
    public JdbcGithubWebhookStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Delivery> delivery(UUID id) {
        return jdbc.sql("SELECT payload_sha256,disposition FROM governance.github_webhook_deliveries WHERE delivery_id=:id")
            .param("id", id).query((r, row) -> new Delivery(r.getString("payload_sha256"),
                GithubWebhookDisposition.valueOf(r.getString("disposition")))).optional();
    }
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
    @Override public void finishDelivery(UUID id, GithubWebhookDisposition disposition) {
        jdbc.sql("""
            UPDATE governance.github_webhook_deliveries SET disposition=:disposition,sealed=TRUE
            WHERE delivery_id=:id AND disposition='RECEIVED' AND NOT sealed
            """).param("disposition", disposition.name()).param("id", id).update();
    }
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
    @Override public Optional<GithubPullRequestView> pullRequest(UUID projectId, UUID repositoryId, String externalId) {
        return jdbc.sql("""
            SELECT * FROM governance.github_pull_request_heads
            WHERE project_id=:project AND repository_id=:repository AND external_id=:pr
            """).param("project", projectId).param("repository", repositoryId).param("pr", externalId)
            .query(JdbcGithubWebhookStore::map).optional();
    }
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
            r.getTimestamp("event_at").toInstant(), r.getObject("current_gate_id", UUID.class));
    }
}
