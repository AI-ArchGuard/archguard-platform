package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

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
    void createsExpectedSchemasAndTables() {
        assertThat(schemaExists("project")).isTrue();
        assertThat(schemaExists("audit")).isTrue();
        assertThat(tableExists("project", "projects")).isTrue();
        assertThat(tableExists("project", "project_members")).isTrue();
        assertThat(tableExists("audit", "audit_records")).isTrue();
    }

    @Test
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
