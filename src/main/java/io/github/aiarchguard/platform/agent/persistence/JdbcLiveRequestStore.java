package io.github.aiarchguard.platform.agent.persistence;

import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingOperations;
import io.github.aiarchguard.platform.agent.internal.LiveRequestStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLiveRequestStore implements LiveRequestStore {
    private final JdbcClient jdbc;

    JdbcLiveRequestStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public void insert(Link link) {
        jdbc.sql("""
            INSERT INTO agent.live_request_links(request_id,project_id,batch_id,template_id,enablement_id,
                credential_version,settings_revision,created_at)
            VALUES (:request,:project,:batch,:template,:enablement,:credential,:revision,:at)
            """).param("request", link.requestId()).param("project", link.projectId())
            .param("batch", link.batchId()).param("template", link.templateId()).param("enablement", link.enablementId())
            .param("credential", link.credentialVersion()).param("revision", link.settingsRevision())
            .param("at", Timestamp.from(link.createdAt())).update();
    }

    @Override public Optional<Link> find(UUID projectId, UUID requestId) {
        return jdbc.sql("SELECT * FROM agent.live_request_links WHERE project_id=:project AND request_id=:request")
            .param("project", projectId).param("request", requestId).query((rs, row) -> new Link(
                rs.getObject("request_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("batch_id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getObject("enablement_id", UUID.class), rs.getObject("credential_version", UUID.class),
                rs.getLong("settings_revision"), rs.getTimestamp("created_at").toInstant())).optional();
    }

    @Override public boolean claim(Link link, UUID requester, Instant at) {
        if (link == null || requester == null || at == null || link.requestId() == null || link.projectId() == null
                || link.batchId() == null || link.templateId() == null || link.enablementId() == null
                || link.credentialVersion() == null || link.createdAt() == null || link.settingsRevision() < 0) return false;
        return jdbc.sql("""
            UPDATE agent.requests r SET state='RUNNING', updated_at=:at
            WHERE r.id=:request AND r.project_id=:project AND r.requester_id=:requester AND r.state='QUEUED'
                AND r.bindings->>'modelProfileVersion'=:profile
                AND r.bindings->>'modelProtocolVersion'='responses-v1-restricted'
                AND r.bindings->>'modelId'='deepseek-flash'
                AND r.bindings->>'priceCatalogVersion'=:catalog
                AND r.bindings->>'outputSchemaVersion'='0.1.0'
                AND r.model_input->>'schemaVersion'='0.1.0'
                AND r.model_input->>'purpose'=r.purpose
                AND ((r.purpose='FINDING_EXPLANATION'
                        AND r.bindings->>'promptVersion'=:explanation
                        AND r.model_input->>'promptVersion'=:explanation)
                    OR (r.purpose='PR_SUMMARY'
                        AND r.bindings->>'promptVersion'=:summary
                        AND r.model_input->>'promptVersion'=:summary))
                AND EXISTS (SELECT 1 FROM agent.live_request_links l
                    WHERE l.request_id=r.id AND l.project_id=r.project_id
                        AND l.batch_id=:batch AND l.template_id=:template AND l.enablement_id=:enablement
                        AND l.credential_version=:credential AND l.settings_revision=:revision AND l.created_at=:created)
            """).param("request", link.requestId()).param("project", link.projectId()).param("requester", requester)
            .param("batch", link.batchId()).param("template", link.templateId()).param("enablement", link.enablementId())
            .param("credential", link.credentialVersion()).param("revision", link.settingsRevision())
            .param("created", Timestamp.from(link.createdAt())).param("at", Timestamp.from(at))
            .param("profile", LiveAccountingOperations.MODEL_PROFILE).param("catalog", LiveCostPolicy.VERSION)
            .param("explanation", LiveAccountingOperations.EXPLANATION_PROMPT).param("summary", LiveAccountingOperations.SUMMARY_PROMPT)
            .update() == 1;
    }
}
