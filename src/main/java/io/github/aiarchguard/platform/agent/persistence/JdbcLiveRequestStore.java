package io.github.aiarchguard.platform.agent.persistence;

import io.github.aiarchguard.platform.agent.internal.LiveRequestStore;
import java.sql.Timestamp;
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
}
