package io.github.aiarchguard.platform.repository.persistence;

import io.github.aiarchguard.platform.repository.RepositoryView;
import io.github.aiarchguard.platform.repository.internal.RepositoryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRepositoryStore implements RepositoryStore {
    private final JdbcClient jdbc;

    public JdbcRepositoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(RepositoryView value) {
        jdbc.sql("""
                INSERT INTO repository.repositories
                    (id, project_id, repository_key, name, mount_path, scanner_identity, created_by, created_at, version)
                VALUES (:id, :projectId, :key, :name, :path, :identity, :actor, :createdAt, :version)
                """)
            .param("id", value.id()).param("projectId", value.projectId()).param("key", value.key())
            .param("name", value.name()).param("path", value.mountPath()).param("identity", value.scannerIdentity())
            .param("actor", value.createdBy()).param("createdAt", Timestamp.from(value.createdAt()))
            .param("version", value.version()).update();
    }

    @Override
    public List<RepositoryView> list(UUID projectId) {
        return jdbc.sql("SELECT * FROM repository.repositories WHERE project_id = :projectId ORDER BY created_at, id")
            .param("projectId", projectId).query(JdbcRepositoryStore::map).list();
    }

    @Override
    public Optional<RepositoryView> find(UUID projectId, UUID repositoryId) {
        return jdbc.sql("SELECT * FROM repository.repositories WHERE project_id = :projectId AND id = :id")
            .param("projectId", projectId).param("id", repositoryId).query(JdbcRepositoryStore::map).optional();
    }

    @Override
    public boolean existsForProject(UUID projectId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM repository.repositories WHERE project_id=:projectId)")
            .param("projectId", projectId).query(Boolean.class).single();
    }

    private static RepositoryView map(ResultSet result, int row) throws SQLException {
        return new RepositoryView(result.getObject("id", UUID.class), result.getObject("project_id", UUID.class),
            result.getString("repository_key"), result.getString("name"), result.getString("mount_path"),
            result.getString("scanner_identity"), result.getObject("created_by", UUID.class),
            result.getTimestamp("created_at").toInstant(), result.getLong("version"));
    }
}
