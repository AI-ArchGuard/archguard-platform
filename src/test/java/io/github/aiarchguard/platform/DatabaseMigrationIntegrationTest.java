package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 验证容器 PostgreSQL 上核心迁移创建预期 Schema/表，并在再次迁移时保持幂等。只覆盖当前测试断言列出的对象和迁移状态。
 */
@SpringBootTest
class DatabaseMigrationIntegrationTest extends PostgresIntegrationTestSupport {
    private final Flyway flyway;
    private final JdbcClient jdbcClient;

    @Autowired
    DatabaseMigrationIntegrationTest(Flyway flyway, JdbcClient jdbcClient) {
        this.flyway = flyway;
        this.jdbcClient = jdbcClient;
    }

    @Test
    // 从系统目录核对预期数据库对象确实存在。
    void createsExpectedSchemasAndTables() {
        assertThat(schemaExists("project")).isTrue();
        assertThat(schemaExists("audit")).isTrue();
        assertThat(tableExists("project", "projects")).isTrue();
        assertThat(tableExists("project", "project_members")).isTrue();
        assertThat(tableExists("audit", "audit_records")).isTrue();
        assertThat(tableExists("governance", "github_pr_head_revisions")).isTrue();
        assertThat(tableExists("agent_document", "document_versions")).isTrue();
        assertThat(tableExists("agent_document", "document_fragments")).isTrue();
        assertThat(tableExists("agent", "requests")).isTrue();
        assertThat(tableExists("agent", "budget_usage")).isTrue();
        assertThat(tableExists("agent", "project_settings")).isTrue();
        assertThat(tableExists("agent", "personal_enablements")).isTrue();
        assertThat(tableExists("agent", "enablement_revocations")).isTrue();
        for (String table : java.util.List.of("live_batches", "live_batch_usage", "live_batch_revocations", "live_budget_usage", "live_attempts", "live_outcomes", "live_request_links")) {
            assertThat(tableExists("agent", table)).isTrue();
        }
        assertThat(jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='agent' AND table_name='live_batches' AND column_name='inventory_proof')")
            .query(Boolean.class).single()).isTrue();
    }

    @Test
    // 初次应用后再次迁移应无待执行项，避免重复改写结构。
    void migrationIsIdempotentAfterInitialApplication() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private boolean schemaExists(String schema) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = :schema)")
            .param("schema", schema)
            .query(Boolean.class)
            .single();
    }

    private boolean tableExists(String schema, String table) {
        return jdbcClient.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = :schema AND table_name = :table
                )
                """)
            .param("schema", schema)
            .param("table", table)
            .query(Boolean.class)
            .single();
    }
}
