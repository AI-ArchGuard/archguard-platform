package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/** Isolated databases keep historic/crash fixtures away from application recovery workers. */
class LiveTemplateSlotMigrationIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String LEGACY = "deepseek-flash-peak-usd-2026-10-06";
    private static final String CONTEXT = "deepseek-flash-context-peak-usd-2026-10-15";
    private static final String SLOTS = "agent.live_template_request_slots";

    @Test void aSecondRequestCannotReplaceAClaimedTemplateEvenBeforeAnyAttemptExists() throws Exception {
        String url = database(null);
        try (var connection = connect(url)) {
            var f = fixture(connection, CONTEXT);
            UUID first = request(connection, f.project());
            link(connection, f, first, f.template());
            assertThat(number(connection, "SELECT count(*) FROM agent.live_attempts")).isZero();
            UUID replacement = request(connection, f.project());
            assertState(connection, linkSql(f, replacement, f.template()), "23505");
            assertThat(number(connection, "SELECT count(*) FROM agent.live_request_links")).isOne();
            assertThat(text(connection, "SELECT request_id::text FROM " + SLOTS)).isEqualTo(first.toString());
        }
    }

    @Test void emptyDatabaseUpgradesToV16AndRepeatIsValidatedAndEmpty() throws Exception {
        String url = newDatabase();
        assertThat(mainMigration(url).migrate().migrationsExecuted).isEqualTo(8);
        var latest = migration(url).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(8);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("16");
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        try (var connection = connect(url)) {
            assertThat(number(connection, "SELECT count(*) FROM " + SLOTS)).isZero();
        }
    }

    @Test void v15UpgradeBackfillsOnlyContextLinksAndPreservesAllExistingHistoryAndChecksums() throws Exception {
        String url = database("15");
        try (var connection = connect(url)) {
            var fresh = fixture(connection, CONTEXT); var old = fixture(connection, LEGACY);
            UUID live = request(connection, fresh.project());
            link(connection, fresh, live, fresh.template());
            link(connection, old, request(connection, old.project()), old.template());
            link(connection, old, request(connection, old.project()), old.template());
            execute(connection, "INSERT INTO agent.live_attempts(request_id,project_id,batch_id,template_id,attempt_id,deployment_id,enablement_id,credential_version,settings_revision,utc_day,price_version,reserved_microusd,created_at) VALUES ('"
                + live + "','" + fresh.project() + "','" + fresh.batch() + "','" + fresh.template() + "','" + UUID.randomUUID() + "','" + fresh.project() + "','"
                + fresh.approval() + "','" + fresh.credential() + "',0,CURRENT_DATE,'" + CONTEXT + "',316373,NOW())");
            execute(connection, "INSERT INTO agent.live_outcomes(request_id,state,usage,actual_microusd,created_at) VALUES ('" + live + "','SETTLED','{\"inputTokens\":1,\"outputTokens\":1}',2,NOW())");
            execute(connection, "UPDATE agent.requests SET state='FAILED',failure='{}' WHERE id='" + live + "'");
            execute(connection, "INSERT INTO agent.live_batch_stops(batch_id,project_id,request_id,code,created_at) VALUES ('" + fresh.batch() + "','" + fresh.project() + "','" + live + "','OUTPUT_INVALID',NOW())");
            Map<String, String> history = history(connection); List<String> checksums = checksums(connection);
            var latest = migration(url).load();
            assertThat(latest.migrate().migrationsExecuted).isOne();
            assertThat(latest.migrate().migrationsExecuted).isZero();
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            assertThat(history(connection)).isEqualTo(history);
            assertThat(checksums(connection)).isEqualTo(checksums);
            assertThat(number(connection, "SELECT count(*) FROM " + SLOTS)).isOne();
            assertThat(text(connection, "SELECT request_id::text FROM " + SLOTS)).isEqualTo(live.toString());
            assertThat(text(connection, "SELECT created_at::text FROM " + SLOTS))
                .isEqualTo(text(connection, "SELECT created_at::text FROM agent.live_request_links WHERE request_id='" + live + "'"));
            assertState(connection, linkSql(fresh, request(connection, fresh.project()), fresh.template()), "23505");
            link(connection, old, request(connection, old.project()), old.template());
            assertThat(number(connection, "SELECT count(*) FROM " + SLOTS)).isOne();
        }
    }

    @Test void duplicateContextHistoryFailsUpgradeWithoutSelectingAWinnerOrRewritingHistory() throws Exception {
        String url = database("15");
        try (var connection = connect(url)) {
            var f = fixture(connection, CONTEXT);
            link(connection, f, request(connection, f.project()), f.template());
            link(connection, f, request(connection, f.project()), f.template());
            Map<String, String> before = history(connection); List<String> checksums = checksums(connection);
            assertThatThrownBy(() -> migration(url).load().migrate()).hasRootCauseInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(rootSqlState(failure)).isEqualTo("23505"));
            assertThat(history(connection)).isEqualTo(before);
            assertThat(checksums(connection)).isEqualTo(checksums);
            assertThat(text(connection, "SELECT COALESCE(to_regclass('" + SLOTS + "')::text,'absent')")).isEqualTo("absent");
            assertThat(number(connection, "SELECT count(*) FROM flyway_agent_schema_history WHERE version='16'")).isZero();
        }
    }

    @Test void legacyLinksRemainUnrestrictedAndSameTemplateInDifferentContextBatchesHasDistinctSlots() throws Exception {
        String url = database(null);
        try (var connection = connect(url)) {
            var old = fixture(connection, LEGACY);
            for (int i = 0; i < 3; i++) link(connection, old, request(connection, old.project()), old.template());
            assertThat(number(connection, "SELECT count(*) FROM " + SLOTS)).isZero();
            var fresh = fixture(connection, CONTEXT);
            link(connection, fresh, request(connection, fresh.project()), fresh.template());
            var another = new Fixture(fresh.project(), fresh.approval(), fresh.credential(), UUID.randomUUID(), fresh.template());
            batch(connection, another, CONTEXT);
            link(connection, another, request(connection, another.project()), another.template());
            assertThat(number(connection, "SELECT count(*) FROM " + SLOTS)).isEqualTo(2);
        }
    }

    @Test void slotsBindTheExactProjectBatchTemplateRequestLinkAndCannotBeMutatedOrDeleted() throws Exception {
        String url = database(null);
        try (var connection = connect(url)) {
            var fresh = fixture(connection, CONTEXT); var other = fixture(connection, CONTEXT); var old = fixture(connection, LEGACY);
            UUID first = request(connection, fresh.project());
            link(connection, fresh, first, fresh.template());
            UUID unrelated = request(connection, fresh.project());
            for (String sql : List.of(slotSql(fresh.project(), fresh.batch(), UUID.randomUUID(), first),
                    slotSql(fresh.project(), other.batch(), other.template(), first),
                    slotSql(other.project(), fresh.batch(), other.template(), first),
                    slotSql(fresh.project(), fresh.batch(), unrelated, unrelated))) {
                assertState(connection, sql, "23503");
            }
            assertState(connection, slotSql(old.project(), old.batch(), old.template(), request(connection, old.project())), "23514");
            assertState(connection, "UPDATE " + SLOTS + " SET request_id='" + unrelated + "'", "P0001");
            assertState(connection, "DELETE FROM " + SLOTS, "P0001");
            assertState(connection, "DELETE FROM agent.live_request_links", "P0001");
            execute(connection, "UPDATE agent.requests SET state='FAILED',failure='{}' WHERE id='" + first + "'");
            assertState(connection, linkSql(fresh, unrelated, fresh.template()), "23505");
            assertThat(text(connection, "SELECT request_id::text FROM " + SLOTS)).isEqualTo(first.toString());
        }
    }

    @Test void simultaneousSameSlotLinksCommitOneWinnerAndRollBackTheOtherRequest() throws Exception {
        String url = database(null);
        Fixture f;
        try (var connection = connect(url)) { f = fixture(connection, CONTEXT); }
        UUID loser = UUID.randomUUID();
        try (var first = connect(url); var pool = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            UUID winner = request(first, f.project()); link(first, f, winner, f.template());
            var started = new CountDownLatch(1);
            var waitingPid = new AtomicLong();
            var future = pool.submit(() -> {
                try (var second = connect(url)) {
                    second.setAutoCommit(false);
                    execute(second, "SET LOCAL statement_timeout='5000ms'");
                    request(second, f.project(), loser);
                    waitingPid.set(number(second, "SELECT pg_backend_pid()"));
                    started.countDown();
                    try {
                        link(second, f, loser, f.template()); second.commit(); return "COMMITTED";
                    } catch (SQLException conflict) { second.rollback(); return conflict.getSQLState(); }
                }
            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                try (var observer = connect(url)) { assertWaitingForTransactionLock(observer, waitingPid.get()); }
                assertThat(future.isDone()).isFalse();
                first.commit();
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo("23505");
            } finally { first.rollback(); future.cancel(true); pool.shutdownNow(); }
            assertThat(number(first, "SELECT count(*) FROM agent.live_request_links")).isOne();
            assertThat(number(first, "SELECT count(*) FROM " + SLOTS)).isOne();
            assertThat(number(first, "SELECT count(*) FROM agent.requests WHERE id='" + loser + "'")).isZero();
        }
    }

    @Test void rolledBackUncommittedCreationLeavesNoSlotAndCannotLeakTheAbortedRequest() throws Exception {
        String url = database(null);
        try (var connection = connect(url)) {
            var f = fixture(connection, CONTEXT);
            connection.setAutoCommit(false);
            UUID aborted = request(connection, f.project()); link(connection, f, aborted, f.template());
            connection.rollback(); connection.setAutoCommit(true);
            assertThat(number(connection, "SELECT count(*) FROM " + SLOTS)).isZero();
            assertThat(number(connection, "SELECT count(*) FROM agent.requests WHERE id='" + aborted + "'")).isZero();
            UUID committed = request(connection, f.project()); link(connection, f, committed, f.template());
            assertThat(text(connection, "SELECT request_id::text FROM " + SLOTS)).isEqualTo(committed.toString());
        }
    }

    private Fixture fixture(Connection connection, String policy) throws SQLException {
        UUID project = UUID.randomUUID(), approval = UUID.randomUUID(), credential = UUID.randomUUID();
        execute(connection, "INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES ('" + project + "','slot-"
            + project.toString().replace("-", "").substring(0, 12) + "','Synthetic template slot',NOW(),'" + project + "')");
        execute(connection, "INSERT INTO agent.personal_enablements(id,project_id,deployment_id,approved_by,approved_at,expires_at,credential_version,acknowledgement) VALUES ('"
            + approval + "','" + project + "','" + project + "','" + project + "',NOW(),NOW()+INTERVAL '1 day','" + credential + "','{}')");
        var f = new Fixture(project, approval, credential, UUID.randomUUID(), request(connection, project));
        batch(connection, f, policy); return f;
    }
    private void batch(Connection connection, Fixture f, String policy) throws SQLException {
        execute(connection, "INSERT INTO agent.live_batches(id,project_id,enablement_id,deployment_id,inventory_id,approved_by,approved_at,expires_at,manifest_sha256,manifest,max_requests,max_cost_microusd,cost_policy_version) VALUES ('"
            + f.batch() + "','" + f.project() + "','" + f.approval() + "','" + f.project() + "','" + f.project() + "','" + f.project()
            + "',NOW(),NOW()+INTERVAL '1 hour','" + "a".repeat(64) + "','{}',2," + (CONTEXT.equals(policy) ? 700000 : 8400) + ",'" + policy + "')");
    }
    private UUID request(Connection connection, UUID project) throws SQLException { return request(connection, project, UUID.randomUUID()); }
    private UUID request(Connection connection, UUID project, UUID id) throws SQLException {
        execute(connection, "INSERT INTO agent.requests(id,project_id,requester_id,idempotency_key,input_digest,trace_id,purpose,state,bindings,created_at,updated_at) VALUES ('"
            + id + "','" + project + "','" + project + "','" + id + "','" + "b".repeat(64) + "','" + "c".repeat(32)
            + "','FINDING_EXPLANATION','RUNNING','{}',NOW(),NOW())"); return id;
    }
    private void link(Connection connection, Fixture f, UUID request, UUID template) throws SQLException { execute(connection, linkSql(f, request, template)); }
    private String linkSql(Fixture f, UUID request, UUID template) {
        return "INSERT INTO agent.live_request_links(request_id,project_id,batch_id,template_id,enablement_id,credential_version,settings_revision,created_at) VALUES ('"
            + request + "','" + f.project() + "','" + f.batch() + "','" + template + "','" + f.approval() + "','" + f.credential() + "',0,NOW())";
    }
    private String slotSql(UUID project, UUID batch, UUID template, UUID request) {
        return "INSERT INTO " + SLOTS + "(project_id,batch_id,template_id,request_id,created_at) VALUES ('" + project + "','" + batch + "','" + template + "','" + request + "',NOW())";
    }
    private Map<String, String> history(Connection connection) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String table : List.of("requests", "personal_enablements", "enablement_revocations", "project_settings", "live_batches", "live_batch_usage",
                "live_batch_revocations", "live_budget_usage", "live_attempts", "live_outcomes", "live_request_links", "live_batch_stops")) {
            values.put(table, text(connection, "SELECT COALESCE(jsonb_agg(to_jsonb(h) ORDER BY to_jsonb(h)::text),'[]'::jsonb)::text FROM agent." + table + " h"));
        }
        return values;
    }
    private List<String> checksums(Connection connection) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT version,checksum FROM flyway_agent_schema_history WHERE type='SQL' AND version::integer<=15 ORDER BY installed_rank")) {
            while (rows.next()) values.add(rows.getString(1) + ":" + rows.getInt(2));
        } return values;
    }
    private String database(String target) throws SQLException {
        String url = newDatabase(); mainMigration(url).migrate();
        var configuration = migration(url); if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        configuration.load().migrate(); return url;
    }
    private String newDatabase() throws SQLException {
        String database = "archguard_template_slot_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect(POSTGRES.getJdbcUrl())) { execute(connection, "CREATE DATABASE " + database); }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }
    private Flyway mainMigration(String url) { return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load(); }
    private org.flywaydb.core.api.configuration.FluentConfiguration migration(String url) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/agent-migration")
            .defaultSchema("public").table("flyway_agent_schema_history").baselineOnMigrate(true).baselineVersion("8");
    }
    private Connection connect(String url) throws SQLException { return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private void execute(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private String text(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
    }
    private long number(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
    }
    private void assertState(Connection connection, String sql, String state) {
        assertThatThrownBy(() -> execute(connection, sql)).isInstanceOf(SQLException.class)
            .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo(state));
    }
    private String rootSqlState(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure instanceof SQLException sql ? sql.getSQLState() : "not-sql";
    }
    private void assertWaitingForTransactionLock(Connection observer, long pid) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            if (number(observer, "SELECT count(*) FROM pg_stat_activity WHERE pid=" + pid
                    + " AND wait_event_type='Lock' AND wait_event='transactionid'") == 1) return;
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        throw new AssertionError("The competing link insert did not wait on the uncommitted slot owner");
    }
    private record Fixture(UUID project, UUID approval, UUID credential, UUID batch, UUID template) { }
}
