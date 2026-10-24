package io.github.aiarchguard.platform.ruleset.persistence;

import io.github.aiarchguard.platform.ruleset.RuleSetVersionView;
import io.github.aiarchguard.platform.ruleset.RuleSetView;
import io.github.aiarchguard.platform.ruleset.internal.RuleSetStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 规则集存储端口的 JDBC 适配器，只访问 ruleset 模块自己的元数据表和版本表。
 * 使用参数绑定保存 YAML 原文；本类不进行成员鉴权，也不为多次调用建立整体事务。
 */
@Repository
public class JdbcRuleSetStore implements RuleSetStore {
    private final JdbcClient jdbc;
    public JdbcRuleSetStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 写入规则集元数据；同一仓库的 key 唯一性由数据库约束判定。 */
    @Override
    public void insert(RuleSetView value) {
        jdbc.sql("""
                INSERT INTO ruleset.rule_sets
                  (id, project_id, repository_id, rule_set_key, name, created_by, created_at)
                VALUES (:id, :projectId, :repositoryId, :key, :name, :actor, :createdAt)
                """)
            .param("id", value.id()).param("projectId", value.projectId()).param("repositoryId", value.repositoryId())
            .param("key", value.key()).param("name", value.name()).param("actor", value.createdBy())
            .param("createdAt", Timestamp.from(value.createdAt())).update();
    }

    /** 同时限定项目和仓库，按创建时间、ID 稳定排列元数据。 */
    @Override
    public List<RuleSetView> list(UUID projectId, UUID repositoryId) {
        return jdbc.sql("SELECT * FROM ruleset.rule_sets WHERE project_id=:projectId AND repository_id=:repositoryId ORDER BY created_at, id")
            .param("projectId", projectId).param("repositoryId", repositoryId).query(JdbcRuleSetStore::mapRuleSet).list();
    }

    /** 在项目、仓库、规则集三个标识都匹配时返回元数据，避免跨归属查询。 */
    @Override
    public Optional<RuleSetView> find(UUID projectId, UUID repositoryId, UUID ruleSetId) {
        return jdbc.sql("SELECT * FROM ruleset.rule_sets WHERE project_id=:projectId AND repository_id=:repositoryId AND id=:id")
            .param("projectId", projectId).param("repositoryId", repositoryId).param("id", ruleSetId)
            .query(JdbcRuleSetStore::mapRuleSet).optional();
    }

    /**
     * 空规则集从 1 开始，其他情况读取最大编号加一；此 SELECT 不锁定或预留编号。
     * 并发读取可能得到同一值，插入时由 uq_ruleset_version 拒绝重复，本适配器不自动重试。
     */
    @Override
    public int nextVersion(UUID ruleSetId) {
        return jdbc.sql("SELECT COALESCE(MAX(version_number), 0) + 1 FROM ruleset.rule_set_versions WHERE rule_set_id=:id")
            .param("id", ruleSetId).query(Integer.class).single();
    }

    /** 追加版本快照，保留 YAML 原文及摘要、Scanner/Schema 版本；没有覆盖历史版本的更新语句。 */
    @Override
    public void insertVersion(RuleSetVersionView value) {
        jdbc.sql("""
                INSERT INTO ruleset.rule_set_versions
                  (id, rule_set_id, version_number, yaml_content, content_sha256, scanner_version,
                   rules_schema_version, created_by, created_at)
                VALUES (:id, :ruleSetId, :version, :yaml, :sha, :scanner, :schema, :actor, :createdAt)
                """)
            .param("id", value.id()).param("ruleSetId", value.ruleSetId()).param("version", value.version())
            .param("yaml", value.yaml()).param("sha", value.sha256()).param("scanner", value.scannerVersion())
            .param("schema", value.schemaVersion()).param("actor", value.createdBy())
            .param("createdAt", Timestamp.from(value.createdAt())).update();
    }

    /** 返回指定规则集的全部版本，最新编号排在前面；归属和授权由应用流程先行检查。 */
    @Override
    public List<RuleSetVersionView> listVersions(UUID ruleSetId) {
        return jdbc.sql("SELECT * FROM ruleset.rule_set_versions WHERE rule_set_id=:id ORDER BY version_number DESC")
            .param("id", ruleSetId).query(JdbcRuleSetStore::mapVersion).list();
    }

    /** 只在 ruleset 模块内连接元数据与版本表，用元数据的项目、仓库归属限定版本查询。 */
    @Override
    public Optional<RuleSetVersionView> findVersion(UUID projectId, UUID repositoryId, UUID versionId) {
        return jdbc.sql("""
                SELECT v.* FROM ruleset.rule_set_versions v
                JOIN ruleset.rule_sets r ON r.id=v.rule_set_id
                WHERE r.project_id=:projectId AND r.repository_id=:repositoryId AND v.id=:id
                """).param("projectId", projectId).param("repositoryId", repositoryId).param("id", versionId)
            .query(JdbcRuleSetStore::mapVersion).optional();
    }

    /** 为 Project 删除保护提供存在性结果；不读取 YAML，也不锁住后续写入。 */
    @Override public boolean existsForProject(UUID projectId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ruleset.rule_sets WHERE project_id=:projectId)")
            .param("projectId",projectId).query(Boolean.class).single();
    }

    /** 把元数据行转换为公开视图，将数据库时间戳恢复为 Instant。 */
    private static RuleSetView mapRuleSet(ResultSet result, int row) throws SQLException {
        return new RuleSetView(result.getObject("id", UUID.class), result.getObject("project_id", UUID.class),
            result.getObject("repository_id", UUID.class), result.getString("rule_set_key"), result.getString("name"),
            result.getObject("created_by", UUID.class), result.getTimestamp("created_at").toInstant());
    }
    /** 还原已存版本，不重新校验 YAML 或计算摘要，保证读取反映原有快照。 */
    private static RuleSetVersionView mapVersion(ResultSet result, int row) throws SQLException {
        return new RuleSetVersionView(result.getObject("id", UUID.class), result.getObject("rule_set_id", UUID.class),
            result.getInt("version_number"), result.getString("yaml_content"), result.getString("content_sha256"),
            result.getString("scanner_version"), result.getString("rules_schema_version"),
            result.getObject("created_by", UUID.class), result.getTimestamp("created_at").toInstant());
    }
}
