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

@Repository
final class JdbcRuleSetStore implements RuleSetStore {
    private final JdbcClient jdbc;
    JdbcRuleSetStore(JdbcClient jdbc) { this.jdbc = jdbc; }

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

    @Override
    public List<RuleSetView> list(UUID projectId, UUID repositoryId) {
        return jdbc.sql("SELECT * FROM ruleset.rule_sets WHERE project_id=:projectId AND repository_id=:repositoryId ORDER BY created_at, id")
            .param("projectId", projectId).param("repositoryId", repositoryId).query(JdbcRuleSetStore::mapRuleSet).list();
    }

    @Override
    public Optional<RuleSetView> find(UUID projectId, UUID repositoryId, UUID ruleSetId) {
        return jdbc.sql("SELECT * FROM ruleset.rule_sets WHERE project_id=:projectId AND repository_id=:repositoryId AND id=:id")
            .param("projectId", projectId).param("repositoryId", repositoryId).param("id", ruleSetId)
            .query(JdbcRuleSetStore::mapRuleSet).optional();
    }

    @Override
    public int nextVersion(UUID ruleSetId) {
        return jdbc.sql("SELECT COALESCE(MAX(version_number), 0) + 1 FROM ruleset.rule_set_versions WHERE rule_set_id=:id")
            .param("id", ruleSetId).query(Integer.class).single();
    }

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

    @Override
    public List<RuleSetVersionView> listVersions(UUID ruleSetId) {
        return jdbc.sql("SELECT * FROM ruleset.rule_set_versions WHERE rule_set_id=:id ORDER BY version_number DESC")
            .param("id", ruleSetId).query(JdbcRuleSetStore::mapVersion).list();
    }

    @Override
    public Optional<RuleSetVersionView> findVersion(UUID projectId, UUID repositoryId, UUID versionId) {
        return jdbc.sql("""
                SELECT v.* FROM ruleset.rule_set_versions v
                JOIN ruleset.rule_sets r ON r.id=v.rule_set_id
                WHERE r.project_id=:projectId AND r.repository_id=:repositoryId AND v.id=:id
                """).param("projectId", projectId).param("repositoryId", repositoryId).param("id", versionId)
            .query(JdbcRuleSetStore::mapVersion).optional();
    }

    @Override public boolean existsForProject(UUID projectId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM ruleset.rule_sets WHERE project_id=:projectId)")
            .param("projectId",projectId).query(Boolean.class).single();
    }

    private static RuleSetView mapRuleSet(ResultSet result, int row) throws SQLException {
        return new RuleSetView(result.getObject("id", UUID.class), result.getObject("project_id", UUID.class),
            result.getObject("repository_id", UUID.class), result.getString("rule_set_key"), result.getString("name"),
            result.getObject("created_by", UUID.class), result.getTimestamp("created_at").toInstant());
    }
    private static RuleSetVersionView mapVersion(ResultSet result, int row) throws SQLException {
        return new RuleSetVersionView(result.getObject("id", UUID.class), result.getObject("rule_set_id", UUID.class),
            result.getInt("version_number"), result.getString("yaml_content"), result.getString("content_sha256"),
            result.getString("scanner_version"), result.getString("rules_schema_version"),
            result.getObject("created_by", UUID.class), result.getTimestamp("created_at").toInstant());
    }
}
