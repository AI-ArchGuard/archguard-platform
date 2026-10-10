package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SyntheticProvenanceMigrationIntegrationTest extends PostgresIntegrationTestSupport {
    @Test void v12HistorySurvivesV13AndOldBatchesHaveNoInventedProof() throws Exception {
        String database = "archguard_provenance_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) { statement.execute("CREATE DATABASE " + database); }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        var previous = migration(url).target(MigrationVersion.fromVersion("12")).load();
        assertThat(previous.migrate().migrationsExecuted).isEqualTo(4);
        try (var connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            UUID project = UUID.randomUUID(), approval = UUID.randomUUID(), batch = UUID.randomUUID();
            statement.execute("INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES ('" + project
                + "','provenance-upgrade','Synthetic upgrade',NOW(),'" + project + "')");
            statement.execute("INSERT INTO agent.personal_enablements(id,project_id,deployment_id,approved_by,approved_at,expires_at,credential_version,acknowledgement) VALUES ('"
                + approval + "','" + project + "','" + project + "','" + project + "',NOW(),NOW()+INTERVAL '1 day','" + project + "','{}')");
            statement.execute("INSERT INTO agent.live_batches(id,project_id,enablement_id,deployment_id,inventory_id,approved_by,approved_at,expires_at,manifest_sha256,manifest,max_requests,max_cost_microusd) VALUES ('"
                + batch + "','" + project + "','" + approval + "','" + project + "','" + project + "','" + project + "',NOW(),NOW()+INTERVAL '1 hour','" + "a".repeat(64) + "','{}',1,4200)");
            String before;
            try (var rows = statement.executeQuery("SELECT row_to_json(b)::text FROM agent.live_batches b")) { rows.next(); before = rows.getString(1); }
            var latest = migration(url).load();
            assertThat(latest.migrate().migrationsExecuted).isOne();
            assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("13");
            assertThat(latest.migrate().migrationsExecuted).isZero();
            try (var rows = statement.executeQuery("SELECT (to_jsonb(b)-'inventory_proof')::text,inventory_proof FROM agent.live_batches b")) {
                rows.next(); assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString(1)))
                    .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(before));
                assertThat(rows.getString(2)).isNull();
            }
            assertThatThrownBy(() -> statement.execute("UPDATE agent.live_batches SET inventory_proof='{}'"))
                .isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.execute("DELETE FROM agent.live_batches")).isInstanceOf(java.sql.SQLException.class);
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        }
    }
    private org.flywaydb.core.api.configuration.FluentConfiguration migration(String url) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/agent-migration").defaultSchema("public").table("flyway_agent_schema_history")
            .baselineOnMigrate(true).baselineVersion("8");
    }
}
