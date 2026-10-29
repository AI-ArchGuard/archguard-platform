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

/**
 * Repository 持久化适配器，仅访问本模块的 repository.repositories 表。
 * SQL 使用命名参数绑定业务值；授权、路径检查和审计由上游应用服务负责。
 */
@Repository
public class JdbcRepositoryStore implements RepositoryStore {
    private final JdbcClient jdbc;

    public JdbcRepositoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 保存登记快照；项目内 key 与 Scanner identity 的唯一性由数据库约束兜底。
     * 这里不先查重或吞掉数据库异常，应用服务据 DuplicateKeyException 统一返回冲突。
     */
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

    /** 按创建时间、UUID 排序列出项目登记；当前查询没有分页，也不读取其他模块的表。 */
    @Override
    public List<RepositoryView> list(UUID projectId) {
        return jdbc.sql("SELECT * FROM repository.repositories WHERE project_id = :projectId ORDER BY created_at, id")
            .param("projectId", projectId).query(JdbcRepositoryStore::map).list();
    }

    /** 通过项目与仓库两个条件校验归属；未命中返回 Optional.empty，业务层决定如何报告未找到。 */
    @Override
    public Optional<RepositoryView> find(UUID projectId, UUID repositoryId) {
        return jdbc.sql("SELECT * FROM repository.repositories WHERE project_id = :projectId AND id = :id")
            .param("projectId", projectId).param("id", repositoryId).query(JdbcRepositoryStore::map).optional();
    }

    /** 使用 EXISTS 提供删除前置检查，不加载全部登记；此查询不承担并发写入的排他保护。 */
    @Override
    public boolean existsForProject(UUID projectId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM repository.repositories WHERE project_id=:projectId)")
            .param("projectId", projectId).query(Boolean.class).single();
    }

    /** 将数据库字段还原为公开快照，时间戳转为 Instant；不在读取时重新生成 identity 或版本。 */
    private static RepositoryView map(ResultSet result, int row) throws SQLException {
        return new RepositoryView(result.getObject("id", UUID.class), result.getObject("project_id", UUID.class),
            result.getString("repository_key"), result.getString("name"), result.getString("mount_path"),
            result.getString("scanner_identity"), result.getObject("created_by", UUID.class),
            result.getTimestamp("created_at").toInstant(), result.getLong("version"));
    }
}
