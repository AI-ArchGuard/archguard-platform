package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在私有 PostgreSQL 数据库验证 V15 上下文费用策略迁移，保留旧批准、请求、尝试和费用事实。数据库约束与串行存储规则共同保证两用途顺序和不可退款历史。
 *
 * Private databases prevent migration fixtures from entering any shared recovery worker.
 */
class ContextCostPolicyMigrationIntegrationTest extends PostgresIntegrationTestSupport {
    private static final String LEGACY = "deepseek-flash-peak-usd-2026-10-06";
    private static final String CONTEXT = "deepseek-flash-context-peak-usd-2026-10-15";

    // 先核实旧约束名称，避免替换错误约束仍让迁移看似成功。
    @Test void v14CostConstraintNamesAreVerifiedBeforeTheirV15Replacement() throws Exception {
        String url = newDatabase(); mainMigration(url).migrate();
        agentMigration(url).target(MigrationVersion.fromVersion("14")).load().migrate();
        try (var connection = connect(url)) {
            assertThat(text(connection, "SELECT conname FROM pg_constraint WHERE conrelid='agent.live_batches'::regclass"
                + " AND contype='c' AND pg_get_constraintdef(oid) LIKE '%max_cost_microusd%'"))
                .isEqualTo("live_batches_check1");
            assertThat(text(connection, "SELECT conname FROM pg_constraint WHERE conrelid='agent.live_attempts'::regclass"
                + " AND contype='c' AND pg_get_constraintdef(oid) LIKE '%reserved_microusd%'"))
                .isEqualTo("live_attempts_reserved_microusd_check");
            assertThat(text(connection, "SELECT conname FROM pg_constraint WHERE conrelid='agent.live_outcomes'::regclass"
                + " AND contype='c' AND pg_get_constraintdef(oid) LIKE '%actual_microusd%'"))
                .isEqualTo("live_outcomes_check");
        }
    }

    @Test void emptyDatabaseMigratesToV15AndRepeatMigrationIsValidatedAndEmpty() throws Exception {
        String url = newDatabase();
        assertThat(mainMigration(url).migrate().migrationsExecuted).isEqualTo(8);
        var current = agentMigration(url).load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(7);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("15");
        assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(current.validateWithResult().validationSuccessful).isTrue();
        try (var connection = connect(url)) {
            assertThat(number(connection, "SELECT count(*) FROM agent.live_batch_stops")).isZero();
        }
    }

    // 种入完整旧版本事实再升级，检查业务历史与迁移校验和保持原样。
    @Test void v14UpgradePreservesOldRequestsApprovalsAttemptsChargesAndChecksums() throws Exception {
        String url = newDatabase(); mainMigration(url).migrate();
        var previous = agentMigration(url).target(MigrationVersion.fromVersion("14")).load();
        assertThat(previous.migrate().migrationsExecuted).isEqualTo(6);
        try (var connection = connect(url)) {
            var fixture = fixture(connection, null);
            attempt(connection, fixture, fixture.first(), fixture.explanation(), "historical-synthetic-price", 4200);
            outcome(connection, fixture.first(), "SETTLED", 273L);
            attempt(connection, fixture, fixture.second(), fixture.summary(), LEGACY, 4200);
            outcome(connection, fixture.second(), "UNKNOWN", null);
            execute(connection, "UPDATE agent.requests SET state='SUCCEEDED',result='{}' WHERE id='" + fixture.first() + "'");
            execute(connection, "UPDATE agent.requests SET state='FAILED',failure='{}' WHERE id='" + fixture.second() + "'");
            execute(connection, "UPDATE agent.live_batch_usage SET reserved_microusd=4200,spent_microusd=273,attempts=2 WHERE batch_id='" + fixture.batch() + "'");
            Map<String, String> before = history(connection, false); List<String> checksums = checksums(connection);
            var current = agentMigration(url).load();
            assertThat(current.migrate().migrationsExecuted).isOne();
            assertThat(current.migrate().migrationsExecuted).isZero();
            assertThat(current.validateWithResult().validationSuccessful).isTrue();
            assertThat(history(connection, true)).isEqualTo(before);
            assertThat(checksums(connection)).isEqualTo(checksums);
            assertThat(text(connection, "SELECT cost_policy_version FROM agent.live_batches WHERE id='" + fixture.batch() + "'")).isEqualTo(LEGACY);
            assertThat(number(connection, "SELECT count(*) FROM agent.live_batch_stops")).isZero();
            for (String sql : List.of("UPDATE agent.live_batches SET cost_policy_version='" + CONTEXT + "'",
                    "DELETE FROM agent.live_attempts", "UPDATE agent.live_outcomes SET actual_microusd=0",
                    "UPDATE agent.personal_enablements SET acknowledgement='{}'", "DELETE FROM agent.live_request_links")) {
                assertState(connection, sql, "P0001");
            }
            assertThat(history(connection, true)).isEqualTo(before);
        }
    }

    // 旧批次继续用旧限制，新上下文策略必须符合获批两次范围。
    @Test void databaseKeepsLegacyBatchLimitsAndRequiresTheTwoCallContextRange() throws Exception {
        String url = latestDatabase();
        try (var connection = connect(url)) {
            var legacy = fixture(connection, LEGACY);
            batch(connection, legacy, UUID.randomUUID(), LEGACY, 1, 4200);
            batch(connection, legacy, UUID.randomUUID(), LEGACY, 20, 84000);
            batch(connection, legacy, UUID.randomUUID(), CONTEXT, 2, 632746);
            batch(connection, legacy, UUID.randomUUID(), CONTEXT, 2, 700000);
            for (var bad : List.of(new BatchLimit(LEGACY, 1, 4201), new BatchLimit(LEGACY, 20, 84001),
                    new BatchLimit(CONTEXT, 2, 632745), new BatchLimit(CONTEXT, 2, 700001),
                    new BatchLimit(CONTEXT, 1, 632746), new BatchLimit(CONTEXT, 3, 700000),
                    new BatchLimit("unknown-policy", 2, 700000))) {
                assertThatThrownBy(() -> batch(connection, legacy, UUID.randomUUID(), bad.policy(), bad.count(), bad.cap()))
                    .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));
            }
        }
    }

    @Test void attemptsBindNewReservationsToNewBatchesWithoutRejectingHistoricPriceLabels() throws Exception {
        String url = latestDatabase();
        try (var connection = connect(url)) {
            var old = fixture(connection, LEGACY); var fresh = fixture(connection, CONTEXT);
            attempt(connection, old, old.first(), old.explanation(), "historical-synthetic-price", 4200);
            attempt(connection, fresh, fresh.first(), fresh.explanation(), CONTEXT, 316373);
            assertThatThrownBy(() -> attempt(connection, fresh, fresh.second(), fresh.summary(), CONTEXT, 4200))
                .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));
            assertThatThrownBy(() -> attempt(connection, fresh, fresh.second(), fresh.summary(), LEGACY, 4200))
                .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));
            assertThatThrownBy(() -> attempt(connection, old, old.second(), old.summary(), CONTEXT, 316373))
                .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));
            assertThatThrownBy(() -> attempt(connection, old, old.second(), old.summary(), "historical-synthetic-price", 316373))
                .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));
        }
    }

    // 已知费用不能超出对应预留，UNKNOWN 保留明确形状而不是零费用。
    @Test void knownOutcomeCannotExceedItsOwnAttemptReservationAndUnknownShapeIsRetained() throws Exception {
        String url = latestDatabase();
        try (var connection = connect(url)) {
            var old = fixture(connection, LEGACY); var fresh = fixture(connection, CONTEXT);
            attempt(connection, old, old.first(), old.explanation(), LEGACY, 4200);
            attempt(connection, fresh, fresh.first(), fresh.explanation(), CONTEXT, 316373);
            assertThatThrownBy(() -> outcome(connection, old.first(), "SETTLED", 4201L))
                .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));
            assertThatThrownBy(() -> outcome(connection, fresh.first(), "SETTLED", 316374L))
                .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23514"));
            outcome(connection, old.first(), "SETTLED", 4200L);
            outcome(connection, fresh.first(), "SETTLED", 316373L);
            attempt(connection, fresh, fresh.second(), fresh.summary(), CONTEXT, 316373);
            assertState(connection, "INSERT INTO agent.live_outcomes(request_id,state,usage,actual_microusd,created_at) VALUES ('"
                + fresh.second() + "','UNKNOWN','{}',0,NOW())", "23514");
            assertState(connection, "INSERT INTO agent.live_outcomes(request_id,state,usage,actual_microusd,created_at) VALUES ('"
                + fresh.second() + "','SETTLED','{}',-1,NOW())", "23514");
            outcome(connection, fresh.second(), "UNKNOWN", null);
            assertThat(number(connection, "SELECT count(*) FROM agent.live_outcomes WHERE state='UNKNOWN' AND usage IS NULL AND actual_microusd IS NULL")).isOne();
        }
    }

    @Test void stopsAreSameProjectSameBatchAppendOnlyAndIdempotent() throws Exception {
        String url = latestDatabase();
        try (var connection = connect(url)) {
            var first = fixture(connection, CONTEXT); var other = fixture(connection, CONTEXT);
            UUID anotherBatch = UUID.randomUUID(); batch(connection, first, anotherBatch, CONTEXT, 2, 700000);
            assertState(connection, stopSql(first.project(), anotherBatch, first.first(), "MODEL_TIMEOUT"), "23503");
            assertState(connection, stopSql(other.project(), first.batch(), first.first(), "MODEL_TIMEOUT"), "23503");
            assertState(connection, stopSql(first.project(), first.batch(), UUID.randomUUID(), "MODEL_TIMEOUT"), "23503");
            assertState(connection, stopSql(first.project(), first.batch(), first.first(), "untrusted-free-text"), "23514");
            execute(connection, stopSql(first.project(), first.batch(), first.first(), "INPUT_TOKEN_LIMIT"));
            execute(connection, stopSql(first.project(), first.batch(), first.second(), "MODEL_TIMEOUT") + " ON CONFLICT(batch_id) DO NOTHING");
            assertThat(number(connection, "SELECT count(*) FROM agent.live_batch_stops WHERE batch_id='" + first.batch() + "'")).isOne();
            assertThat(text(connection, "SELECT code FROM agent.live_batch_stops WHERE batch_id='" + first.batch() + "'")).isEqualTo("INPUT_TOKEN_LIMIT");
            assertState(connection, "UPDATE agent.live_batch_stops SET code='INTERNAL_ERROR'", "P0001");
            assertState(connection, "DELETE FROM agent.live_batch_stops", "P0001");
        }
    }

    // 摘要依赖解释成功且结算；仅有费用结果或已停批都不能继续。
    @Test void serialStoreRequiresSuccessfulExplanationNotJustSettledFeesAndStopsBlockLaterWork() throws Exception {
        String url = latestDatabase();
        try (var connection = connect(url)) {
            var fixture = fixture(connection, CONTEXT); var harness = store(url);
            var batch = harness.store().batch(fixture.project(), fixture.batch()).orElseThrow();
            assertThat(batch.costPolicyVersion()).isEqualTo(CONTEXT);
            harness.transaction().executeWithoutResult(status -> harness.store().requireSerialProgress(batch, fixture.first()));
            assertThatThrownBy(() -> harness.transaction().executeWithoutResult(status -> harness.store().requireSerialProgress(batch, fixture.second())))
                .isInstanceOf(AgentEnablementInvalidStateException.class);
            attempt(connection, fixture, fixture.first(), fixture.explanation(), CONTEXT, 316373);
            assertThatThrownBy(() -> harness.transaction().executeWithoutResult(status -> harness.store().requireSerialProgress(batch, fixture.second())))
                .isInstanceOf(AgentEnablementInvalidStateException.class);
            outcome(connection, fixture.first(), "SETTLED", 273L);
            assertThatThrownBy(() -> harness.transaction().executeWithoutResult(status -> harness.store().requireSerialProgress(batch, fixture.second())))
                .isInstanceOf(AgentEnablementInvalidStateException.class);
            execute(connection, "UPDATE agent.requests SET state='SUCCEEDED',result='{}' WHERE id='" + fixture.first() + "'");
            harness.transaction().executeWithoutResult(status -> harness.store().requireSerialProgress(batch, fixture.second()));
            harness.transaction().executeWithoutResult(status -> harness.store().stop(fixture.project(), fixture.batch(), fixture.first(), "OUTPUT_INVALID", Instant.now()));
            harness.transaction().executeWithoutResult(status -> harness.store().stop(fixture.project(), fixture.batch(), fixture.second(), "MODEL_TIMEOUT", Instant.now()));
            assertThatThrownBy(() -> harness.transaction().executeWithoutResult(status -> harness.store().requireSerialProgress(batch, fixture.second())))
                .isInstanceOf(AgentEnablementInvalidStateException.class);
            assertThatThrownBy(() -> harness.transaction().executeWithoutResult(status -> harness.store().stop(fixture.project(), fixture.batch(), UUID.randomUUID(), "MODEL_TIMEOUT", Instant.now())))
                .isInstanceOf(AgentEnablementInvalidStateException.class);
            assertThat(number(connection, "SELECT count(*) FROM agent.live_batch_stops")).isOne();
            assertThatThrownBy(() -> harness.store().requireSerialProgress(batch, fixture.second())).isInstanceOf(AgentUnavailableException.class);
        }
    }

    // 两次额度只能对应两个不同用途，不能通过重复用途或复用模板绕过。
    @Test void serialStoreRejectsDuplicatePurposesAndTemplateReuse() throws Exception {
        String url = latestDatabase();
        try (var connection = connect(url)) {
            var fixture = fixture(connection, CONTEXT); var harness = store(url);
            attempt(connection, fixture, fixture.first(), fixture.explanation(), CONTEXT, 316373);
            outcome(connection, fixture.first(), "SETTLED", 273L);
            execute(connection, "UPDATE agent.requests SET state='SUCCEEDED',result='{}' WHERE id='" + fixture.first() + "'");
            var batch = harness.store().batch(fixture.project(), fixture.batch()).orElseThrow();
            UUID duplicatePurpose = request(connection, fixture.project(), "FINDING_EXPLANATION", false);
            link(connection, fixture, duplicatePurpose, fixture.summary());
            UUID duplicateTemplate = request(connection, fixture.project(), "PR_SUMMARY", false);
            link(connection, fixture, duplicateTemplate, fixture.explanation());
            for (UUID invalid : List.of(duplicatePurpose, duplicateTemplate, UUID.randomUUID())) {
                assertThatThrownBy(() -> harness.transaction().executeWithoutResult(status -> harness.store().requireSerialProgress(batch, invalid)))
                    .isInstanceOf(AgentEnablementInvalidStateException.class);
            }
        }
    }

    private Fixture fixture(Connection connection, String policy) throws SQLException {
        UUID project = UUID.randomUUID(), approval = UUID.randomUUID(), credential = UUID.randomUUID(), batch = UUID.randomUUID();
        execute(connection, "INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES ('" + project
            + "','cost-" + project.toString().replace("-", "").substring(0, 12) + "','Synthetic cost migration',NOW(),'" + project + "')");
        execute(connection, "INSERT INTO agent.personal_enablements(id,project_id,deployment_id,approved_by,approved_at,expires_at,credential_version,acknowledgement) VALUES ('"
            + approval + "','" + project + "','" + project + "','" + project + "',NOW(),NOW()+INTERVAL '1 day','" + credential + "','{}')");
        UUID explanation = request(connection, project, "FINDING_EXPLANATION", true);
        UUID summary = request(connection, project, "PR_SUMMARY", true);
        UUID first = request(connection, project, "FINDING_EXPLANATION", false);
        UUID second = request(connection, project, "PR_SUMMARY", false);
        var fixture = new Fixture(project, approval, credential, batch, explanation, summary, first, second);
        batch(connection, fixture, batch, policy, 2, CONTEXT.equals(policy) ? 700000 : 8400);
        execute(connection, "INSERT INTO agent.live_batch_usage(batch_id) VALUES ('" + batch + "')");
        link(connection, fixture, first, explanation); link(connection, fixture, second, summary);
        return fixture;
    }

    private void batch(Connection connection, Fixture fixture, UUID batch, String policy, int count, long cap) throws SQLException {
        String columns = policy == null ? "" : ",cost_policy_version";
        String value = policy == null ? "" : ",'" + policy + "'";
        String manifest = "{\"schemaVersion\":\"0.1.0\",\"projectId\":\"" + fixture.project() + "\",\"templates\":[]}";
        execute(connection, "INSERT INTO agent.live_batches(id,project_id,enablement_id,deployment_id,inventory_id,approved_by,approved_at,expires_at,manifest_sha256,manifest,max_requests,max_cost_microusd,inventory_proof"
            + columns + ") VALUES ('" + batch + "','" + fixture.project() + "','" + fixture.approval() + "','" + fixture.project() + "','" + fixture.project() + "','" + fixture.project()
            + "',NOW(),NOW()+INTERVAL '1 hour','" + "a".repeat(64) + "','" + manifest + "'," + count + "," + cap + ",NULL" + value + ")");
    }

    private UUID request(Connection connection, UUID project, String purpose, boolean terminal) throws SQLException {
        UUID id = UUID.randomUUID();
        execute(connection, "INSERT INTO agent.requests(id,project_id,requester_id,idempotency_key,input_digest,trace_id,purpose,state,bindings,failure,created_at,updated_at) VALUES ('"
            + id + "','" + project + "','" + project + "','" + id + "','" + "b".repeat(64) + "','" + "c".repeat(32) + "','" + purpose + "','"
            + (terminal ? "FAILED" : "RUNNING") + "','{}'," + (terminal ? "'{}'" : "NULL") + ",NOW(),NOW())");
        return id;
    }

    private void link(Connection connection, Fixture f, UUID request, UUID template) throws SQLException {
        execute(connection, "INSERT INTO agent.live_request_links(request_id,project_id,batch_id,template_id,enablement_id,credential_version,settings_revision,created_at) VALUES ('"
            + request + "','" + f.project() + "','" + f.batch() + "','" + template + "','" + f.approval() + "','" + f.credential() + "',0,NOW())");
    }

    private void attempt(Connection connection, Fixture f, UUID request, UUID template, String price, long reservation) throws SQLException {
        execute(connection, "INSERT INTO agent.live_attempts(request_id,project_id,batch_id,template_id,attempt_id,deployment_id,enablement_id,credential_version,settings_revision,utc_day,price_version,reserved_microusd,created_at) VALUES ('"
            + request + "','" + f.project() + "','" + f.batch() + "','" + template + "','" + UUID.randomUUID() + "','" + f.project() + "','" + f.approval() + "','" + f.credential()
            + "',0,CURRENT_DATE,'" + price + "'," + reservation + ",NOW())");
    }

    private void outcome(Connection connection, UUID request, String state, Long cost) throws SQLException {
        execute(connection, "INSERT INTO agent.live_outcomes(request_id,state,usage,actual_microusd,created_at) VALUES ('" + request + "','" + state
            + "'," + (cost == null ? "NULL,NULL" : "'{\"inputTokens\":1,\"outputTokens\":1}'," + cost) + ",NOW())");
    }

    private String stopSql(UUID project, UUID batch, UUID request, String code) {
        return "INSERT INTO agent.live_batch_stops(project_id,batch_id,request_id,code,created_at) VALUES ('" + project + "','" + batch + "','" + request + "','" + code + "',NOW())";
    }
    private Map<String, String> history(Connection connection, boolean upgraded) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : List.of("requests", "personal_enablements", "project_settings", "live_batches", "live_batch_usage", "live_batch_revocations", "live_budget_usage", "live_attempts", "live_outcomes", "live_request_links")) {
            String value = upgraded && "live_batches".equals(table) ? "(to_jsonb(h)-'cost_policy_version')" : "to_jsonb(h)";
            result.put(table, text(connection, "SELECT COALESCE(jsonb_agg(" + value + " ORDER BY " + value + "::text),'[]'::jsonb)::text FROM agent." + table + " h"));
        }
        return result;
    }
    private List<String> checksums(Connection connection) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT version,checksum FROM flyway_agent_schema_history WHERE type='SQL' AND version<>'15' ORDER BY installed_rank")) {
            while (rows.next()) values.add(rows.getString(1) + ":" + rows.getInt(2));
        }
        return values;
    }
    private StoreHarness store(String url) throws Exception {
        var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        // Keep the production repository package-private; do not enlarge its API only for tests.
        var constructor = Class.forName("io.github.aiarchguard.platform.agent.persistence.JdbcLiveAccountingStore")
            .getDeclaredConstructor(JdbcClient.class, ObjectMapper.class);
        assertThat(constructor.trySetAccessible()).isTrue();
        var store = (LiveAccountingStore) constructor.newInstance(JdbcClient.create(source), new ObjectMapper());
        return new StoreHarness(store, new TransactionTemplate(new DataSourceTransactionManager(source)));
    }
    private String latestDatabase() throws SQLException {
        String url = newDatabase(); mainMigration(url).migrate(); agentMigration(url).load().migrate(); return url;
    }
    private String newDatabase() throws SQLException {
        String database = "archguard_context_cost_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect(POSTGRES.getJdbcUrl())) { execute(connection, "CREATE DATABASE " + database); }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }
    private Flyway mainMigration(String url) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load();
    }
    private org.flywaydb.core.api.configuration.FluentConfiguration agentMigration(String url) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/agent-migration").defaultSchema("public").table("flyway_agent_schema_history")
            .baselineOnMigrate(true).baselineVersion("8").target(MigrationVersion.fromVersion("15"));
    }
    private Connection connect(String url) throws SQLException { return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private void execute(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private String text(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
    }
    private long number(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
    }
    private void assertState(Connection connection, String sql, String expected) {
        assertThatThrownBy(() -> execute(connection, sql)).isInstanceOf(SQLException.class)
            .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo(expected));
    }
    private record Fixture(UUID project, UUID approval, UUID credential, UUID batch, UUID explanation, UUID summary, UUID first, UUID second) { }
    private record BatchLimit(String policy, int count, long cap) { }
    private record StoreHarness(LiveAccountingStore store, TransactionTemplate transaction) { }
}
