package io.github.aiarchguard.platform.project.persistence;

import io.github.aiarchguard.platform.project.internal.application.ProjectRepository;
import io.github.aiarchguard.platform.project.internal.application.ProjectAccess;
import io.github.aiarchguard.platform.project.internal.domain.Project;
import io.github.aiarchguard.platform.project.internal.domain.ProjectId;
import io.github.aiarchguard.platform.project.internal.domain.ProjectKey;
import io.github.aiarchguard.platform.project.internal.domain.ProjectMember;
import io.github.aiarchguard.platform.project.internal.domain.ProjectName;
import io.github.aiarchguard.platform.project.internal.domain.ProjectRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
                SELECT p.id, p.project_key, p.name, p.created_by, p.created_at, p.version
                FROM project.projects p
                JOIN project.project_members m ON m.project_id = p.id
                WHERE p.id = :projectId AND m.actor_id = :actorId
                """)
            .param("projectId", projectId)
            .param("actorId", actorId)
            .query(JdbcProjectRepository::mapProject)
            .optional();
    }

    @Override
    public Optional<ProjectAccess> lockForActor(UUID projectId, UUID actorId) {
        return jdbcClient.sql("""
                SELECT p.id, p.project_key, p.name, p.created_by, p.created_at, p.version, m.role
                FROM project.projects p
                JOIN project.project_members m ON m.project_id = p.id
                WHERE p.id = :projectId AND m.actor_id = :actorId
                FOR UPDATE OF p
                """)
            .param("projectId", projectId)
            .param("actorId", actorId)
            .query((resultSet, rowNumber) -> new ProjectAccess(
                mapProject(resultSet, rowNumber), ProjectRole.valueOf(resultSet.getString("role"))))
            .optional();
    }

    @Override
    public List<Project> findPageForActor(UUID actorId, int limit, long offset) {
        return jdbcClient.sql("""
                SELECT p.id, p.project_key, p.name, p.created_by, p.created_at, p.version
                FROM project.projects p
                JOIN project.project_members m ON m.project_id = p.id
                WHERE m.actor_id = :actorId
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT :limit OFFSET :offset
                """)
            .param("actorId", actorId)
            .param("limit", limit)
            .param("offset", offset)
            .query(JdbcProjectRepository::mapProject)
            .list();
    }

    @Override
    public long countForActor(UUID actorId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM project.project_members
                WHERE actor_id = :actorId
                """)
            .param("actorId", actorId)
            .query(Long.class)
            .single();
    }

    @Override
    public int updateName(UUID projectId, ProjectName name, long expectedVersion) {
        return jdbcClient.sql("""
                UPDATE project.projects
                SET name = :name, version = version + 1
                WHERE id = :projectId AND version = :expectedVersion
                """)
            .param("name", name.value())
            .param("projectId", projectId)
            .param("expectedVersion", expectedVersion)
            .update();
    }

    @Override
    public int delete(UUID projectId, long expectedVersion) {
        return jdbcClient.sql("""
                DELETE FROM project.projects
                WHERE id = :projectId AND version = :expectedVersion
                """)
            .param("projectId", projectId)
            .param("expectedVersion", expectedVersion)
            .update();
    }

    @Override
    public List<ProjectMember> findMemberPage(UUID projectId, int limit, long offset) {
        return jdbcClient.sql("""
                SELECT actor_id, role, created_at
                FROM project.project_members
                WHERE project_id = :projectId
                ORDER BY created_at, actor_id
                LIMIT :limit OFFSET :offset
                """)
            .param("projectId", projectId)
            .param("limit", limit)
            .param("offset", offset)
            .query(JdbcProjectRepository::mapMember)
            .list();
    }

    @Override
    public long countMembers(UUID projectId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM project.project_members
                WHERE project_id = :projectId
                """)
            .param("projectId", projectId)
            .query(Long.class)
            .single();
    }

    @Override
    public Optional<ProjectMember> findMember(UUID projectId, UUID actorId) {
        return jdbcClient.sql("""
                SELECT actor_id, role, created_at
                FROM project.project_members
                WHERE project_id = :projectId AND actor_id = :actorId
                """)
            .param("projectId", projectId)
            .param("actorId", actorId)
            .query(JdbcProjectRepository::mapMember)
            .optional();
    }

    @Override
    public void upsertMember(UUID projectId, UUID actorId, ProjectRole role, Instant createdAt) {
        jdbcClient.sql("""
                INSERT INTO project.project_members (project_id, actor_id, role, created_at)
                VALUES (:projectId, :actorId, :role, :createdAt)
                ON CONFLICT (project_id, actor_id)
                DO UPDATE SET role = EXCLUDED.role
                """)
            .param("projectId", projectId)
            .param("actorId", actorId)
            .param("role", role.name())
            .param("createdAt", Timestamp.from(createdAt))
            .update();
    }

    @Override
    public int deleteMember(UUID projectId, UUID actorId) {
        return jdbcClient.sql("""
                DELETE FROM project.project_members
                WHERE project_id = :projectId AND actor_id = :actorId
                """)
            .param("projectId", projectId)
            .param("actorId", actorId)
            .update();
    }

    @Override
    public long countMaintainers(UUID projectId) {
        return jdbcClient.sql("""
                SELECT count(*)
                FROM project.project_members
                WHERE project_id = :projectId AND role = 'MAINTAINER'
                """)
            .param("projectId", projectId)
            .query(Long.class)
            .single();
    }

    private static Project mapProject(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Project(
            new ProjectId(resultSet.getObject("id", UUID.class)),
            new ProjectKey(resultSet.getString("project_key")),
            new ProjectName(resultSet.getString("name")),
            resultSet.getObject("created_by", UUID.class),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getLong("version"));
    }

    private static ProjectMember mapMember(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProjectMember(
            resultSet.getObject("actor_id", UUID.class),
            ProjectRole.valueOf(resultSet.getString("role")),
            resultSet.getTimestamp("created_at").toInstant());
    }
}
