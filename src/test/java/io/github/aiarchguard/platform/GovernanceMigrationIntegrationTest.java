package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class GovernanceMigrationIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired JdbcClient jdbc;

    @Test void cleanDatabaseAndSealedBaselineAreImmutable() {
        assertThat(jdbc.sql("SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema='governance' AND table_name='baseline_versions')")
            .query(Boolean.class).single()).isTrue();
        UUID project = UUID.randomUUID(), repository = UUID.randomUUID(), rules = UUID.randomUUID();
        UUID scope = UUID.randomUUID(), version = UUID.randomUUID(), job = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO scanjob.scan_jobs(id,project_id,repository_id,rule_set_version_id,idempotency_key,
              request_sha256,status,created_by,created_at)
            VALUES (:job,:project,:repository,:rules,:key,:sha,'SUCCEEDED',:project,NOW())
            """).param("job", job).param("project", project).param("repository", repository)
            .param("rules", rules).param("key", UUID.randomUUID().toString())
            .param("sha", "a".repeat(64)).update();
        jdbc.sql("""
            INSERT INTO governance.baseline_scopes(id,project_id,repository_id,target_branch,rule_set_version_id)
            VALUES (:scope,:project,:repository,'main',:rules)
            """).param("scope", scope).param("project", project).param("repository", repository)
            .param("rules", rules).update();
        jdbc.sql("""
            INSERT INTO governance.baseline_versions(id,scope_id,version_number,scan_job_id,commit_sha,
              report_sha256,fingerprint_version,created_by,created_at)
            VALUES (:version,:scope,1,:job,:commit,:sha,'platform-finding-v1',:project,NOW())
            """).param("version", version).param("scope", scope).param("job", job)
            .param("commit", "b".repeat(40)).param("sha", "a".repeat(64))
            .param("project", project).update();
        jdbc.sql("UPDATE governance.baseline_versions SET sealed=TRUE WHERE id=:id")
            .param("id", version).update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE governance.baseline_versions SET commit_sha=:sha WHERE id=:id")
            .param("sha", "c".repeat(40)).param("id", version).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM governance.baseline_versions WHERE id=:id")
            .param("id", version).update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.sql("""
            INSERT INTO governance.baseline_findings(baseline_version_id,fingerprint,payload_sha256,
              rule_id,rule_version,severity,scanner_finding_id)
            VALUES (:id,:sha,:sha,'rule','0.1.0','high','finding')
            """).param("id", version).param("sha", "d".repeat(64)).update()).isInstanceOf(Exception.class);
    }

    @Test void upgradesAnExistingV2DatabaseWithoutRewritingItsMigrations() throws Exception {
        String database = "archguard_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String rootUrl = POSTGRES.getJdbcUrl();
        try (var connection = DriverManager.getConnection(rootUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String upgradeUrl = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
            + POSTGRES.getMappedPort(5432) + "/" + database;
        Flyway v2 = Flyway.configure().dataSource(upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target(MigrationVersion.fromVersion("2")).load();
        assertThat(v2.migrate().migrationsExecuted).isEqualTo(2);
        Flyway latest = Flyway.configure().dataSource(upgradeUrl, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("6");
    }

    @Test void upgradesV3WithoutRewritingHistory() throws Exception {
        String database = "archguard_governance_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                 POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
            + POSTGRES.getMappedPort(5432) + "/" + database;
        Flyway v3 = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target(MigrationVersion.fromVersion("3")).load();
        assertThat(v3.migrate().migrationsExecuted).isEqualTo(3);
        Flyway latest = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("6");
    }

    @Test void upgradesV4WithoutRewritingHistory() throws Exception {
        String database = "archguard_github_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                 POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":"
            + POSTGRES.getMappedPort(5432) + "/" + database;
        Flyway v4 = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .target(MigrationVersion.fromVersion("4")).load();
        assertThat(v4.migrate().migrationsExecuted).isEqualTo(4);
        Flyway latest = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("6");
    }
}
