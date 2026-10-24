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

/**
 * Project 持久化端口的 JDBC 适配器，只访问本模块的项目表和成员表。
 * SQL 使用命名占位符和 param 绑定值，避免把输入拼成 SQL；查询结果再重建内部领域对象。
 * 本类不自行开启事务，组合写入、行锁的持有期及审计一致性由上层事务服务负责。
 */
@Repository
public class JdbcProjectRepository implements ProjectRepository {
    private final JdbcClient jdbcClient;

    public JdbcProjectRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 顺序插入项目及创建者的 MAINTAINER 成员关系；项目版本使用数据库默认值零。
     * 两条 SQL 依赖调用方的创建事务，才能避免出现只有项目、没有维护者的部分写入。
     */
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

    /**
     * 通过成员表联接和 actorId 条件限制可见性，非成员与项目不存在都会得到空结果。
     * 授权范围由 SQL 过滤，应用层无需先取出所有项目再筛选。
     */
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

    /**
     * 读取操作者的成员角色，并以 FOR UPDATE OF p 只锁定项目行，锁持续到调用方事务结束。
     * 遵循此约定的同项目写命令在项目行上串行，成员数量检查和后续变更可在同一持锁事务中完成；
     * 这不是对成员表所有行加锁，调用方仍需根据返回角色判断是否为维护者。
     */
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

    /** 只读授权查询，同时映射项目与成员角色；与 lockForActor 不同，此处不申请项目行锁。 */
    @Override
    public Optional<ProjectAccess> findAccessForActor(UUID projectId, UUID actorId) {
        return jdbcClient.sql("""
                SELECT p.id, p.project_key, p.name, p.created_by, p.created_at, p.version, m.role
                FROM project.projects p
                JOIN project.project_members m ON m.project_id = p.id
                WHERE p.id = :projectId AND m.actor_id = :actorId
                """)
            .param("projectId", projectId)
            .param("actorId", actorId)
            .query((resultSet, rowNumber) -> new ProjectAccess(
                mapProject(resultSet, rowNumber), ProjectRole.valueOf(resultSet.getString("role"))))
            .optional();
    }

    /**
     * 仅分页返回该操作者加入的项目，按创建时间倒序并用 UUID 打破同时间的排序平局。
     * 固定排序便于分页，但 offset 分页本身不冻结并发新增或删除带来的页内容变化。
     */
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

    /** 每个项目与 actor 的成员关系唯一，因而可直接统计成员关系得到该 actor 的项目总数。 */
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

    /**
     * 把期望版本写入 WHERE 条件，并在命中时原子递增版本，防止过期客户端覆盖新名称。
     * 返回零行由应用用例解释为版本冲突，本层不把它伪装成写入成功。
     */
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

    /**
     * 按项目 ID 和期望版本物理删除，成员由已定义的外键级联删除。
     * 本 SQL 不检查其他模块内容，也不删除 audit 模块保存的历史审计。
     */
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

    /** 读取已授权项目的成员，按加入时间和 actor UUID 稳定排序；这里不重复校验当前操作者。 */
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

    /** 统计项目全部成员，与成员分页共用 projectId 范围。 */
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

    /** 按项目和目标 actor 的联合键读取成员；调用方用其角色决定是否需检查最后维护者。 */
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

    /**
     * 利用成员联合唯一键新增或更新：已存在时只更新角色，保留最初的 created_at。
     * SQL 负责写入语义，能否降级维护者由上层在持有项目行锁时先行判断。
     */
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

    /** 删除联合键指定的成员，返回零行让上层识别目标成员不存在。 */
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

    /**
     * 只统计 MAINTAINER 角色，不把 Viewer 算入可管理项目的人数。
     * 查询本身不加锁，最后维护者保护依赖调用方先锁项目行再计数和变更。
     */
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

    /**
     * 将数据库 UUID、时间戳和版本映射成领域项目；key/name 经值对象构造器重新接受领域约束。
     * created_at 转为 Instant，领域层无需接触 ResultSet 或数据库时间类型。
     */
    private static Project mapProject(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Project(
            new ProjectId(resultSet.getObject("id", UUID.class)),
            new ProjectKey(resultSet.getString("project_key")),
            new ProjectName(resultSet.getString("name")),
            resultSet.getObject("created_by", UUID.class),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getLong("version"));
    }

    /** 把成员行重建为领域记录，角色名称按枚举解析；非法持久化值不会被静默替换为默认角色。 */
    private static ProjectMember mapMember(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProjectMember(
            resultSet.getObject("actor_id", UUID.class),
            ProjectRole.valueOf(resultSet.getString("role")),
            resultSet.getTimestamp("created_at").toInstant());
    }
}
