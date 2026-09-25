package io.github.aiarchguard.platform.governance.persistence;

import io.github.aiarchguard.platform.governance.GithubRepositoryLinkView;
import io.github.aiarchguard.platform.governance.internal.GithubRepositoryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGithubRepositoryStore implements GithubRepositoryStore {
    private final JdbcClient jdbc;
    public JdbcGithubRepositoryStore(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public Optional<GithubRepositoryLinkView> find(UUID projectId, UUID repositoryId) {
        return jdbc.sql("SELECT * FROM governance.github_repository_links WHERE project_id=:project AND repository_id=:repository")
            .param("project", projectId).param("repository", repositoryId)
            .query(JdbcGithubRepositoryStore::map).optional();
    }
    @Override public Optional<GithubRepositoryLinkView> byExternalId(String externalId) {
        return jdbc.sql("SELECT * FROM governance.github_repository_links WHERE provider_repository_id=:external")
            .param("external", externalId).query(JdbcGithubRepositoryStore::map).optional();
    }
    @Override public boolean insert(GithubRepositoryLinkView value) {
        return jdbc.sql("""
            INSERT INTO governance.github_repository_links(repository_id,project_id,provider,
              provider_repository_id,owner_name,repository_name,created_by,created_at)
            VALUES (:repository,:project,'github',:external,:owner,:name,:actor,:at)
            ON CONFLICT DO NOTHING
            """).param("repository", value.repositoryId()).param("project", value.projectId())
            .param("external", value.providerRepositoryId()).param("owner", value.ownerName())
            .param("name", value.repositoryName()).param("actor", value.createdBy())
            .param("at", Timestamp.from(value.createdAt())).update() == 1;
    }
    private static GithubRepositoryLinkView map(ResultSet r, int row) throws SQLException {
        return new GithubRepositoryLinkView(r.getObject("project_id", UUID.class),
            r.getObject("repository_id", UUID.class), r.getString("provider"),
            r.getString("provider_repository_id"), r.getString("owner_name"),
            r.getString("repository_name"), r.getObject("created_by", UUID.class),
            r.getTimestamp("created_at").toInstant());
    }
}
