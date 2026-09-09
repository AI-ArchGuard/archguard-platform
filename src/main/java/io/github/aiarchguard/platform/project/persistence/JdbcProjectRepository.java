package io.github.aiarchguard.platform.project.persistence;

import io.github.aiarchguard.platform.project.internal.application.ProjectRepository;
import io.github.aiarchguard.platform.project.internal.domain.Project;
import io.github.aiarchguard.platform.project.internal.domain.ProjectId;
import io.github.aiarchguard.platform.project.internal.domain.ProjectKey;
import io.github.aiarchguard.platform.project.internal.domain.ProjectName;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectRepository implements ProjectRepository {
    private final JdbcClient jdbcClient;

    public JdbcProjectRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void insertWithMaintainer(Project project) {
        jdbcClient.sql("""
                INSERT INTO project.projects (id, project_key, name, created_by, created_at)
                VALUES (:id, :projectKey, :name, :createdBy, :createdAt)
                """)
            .param("id", project.id().value())
            .param("projectKey", project.key().value())
            .param("name", project.name().value())
            .param("createdBy", project.createdBy())
            .param("createdAt", Timestamp.from(project.createdAt()))
            .update();

        jdbcClient.sql("""
                INSERT INTO project.project_members (project_id, actor_id, role, created_at)
                VALUES (:projectId, :actorId, 'MAINTAINER', :createdAt)
                """)
            .param("projectId", project.id().value())
            .param("actorId", project.createdBy())
            .param("createdAt", Timestamp.from(project.createdAt()))
            .update();
    }

    @Override
    public Optional<Project> findForActor(UUID projectId, UUID actorId) {
        return jdbcClient.sql("""
                SELECT p.id, p.project_key, p.name, p.created_by, p.created_at
                FROM project.projects p
                JOIN project.project_members m ON m.project_id = p.id
                WHERE p.id = :projectId AND m.actor_id = :actorId
                """)
            .param("projectId", projectId)
            .param("actorId", actorId)
            .query(JdbcProjectRepository::mapProject)
            .optional();
    }

    private static Project mapProject(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Project(
            new ProjectId(resultSet.getObject("id", UUID.class)),
            new ProjectKey(resultSet.getString("project_key")),
            new ProjectName(resultSet.getString("name")),
            resultSet.getObject("created_by", UUID.class),
            resultSet.getTimestamp("created_at").toInstant());
    }
}
