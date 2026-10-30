package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

/** 随机隔离数据库；迁移前后显式断言目标版本，再验证 V17 的不可变历史和跨行约束。 */
class PersonalDailyAuthorizationMigrationIntegrationTest extends PostgresIntegrationTestSupport {
    static final String HASH = "a".repeat(64), OTHER = "b".repeat(64);
    static final String POLICY = "personal-selected-nonsensitive-0.1.0";
    static final String AT = "2026-10-30T02:00:00Z", EXPIRY = "2026-10-30T03:00:00Z";
    static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID DEPLOYMENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID CREDENTIAL = UUID.fromString("33333333-3333-4333-8333-333333333333");
    final ObjectMapper mapper = new ObjectMapper();

    @Test void emptyDatabaseMigratesExactlyThrough17AndRepeatIsZeroWithoutV18() throws Exception {
        String url = database16(); var migration = agent(url).target("17").load();
        assertThat(migration.migrate().migrationsExecuted).as("one real V17 migration must be present").isOne();
        assertThat(migration.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("17"));
        assertThat(migration.migrate().migrationsExecuted).isZero(); migration.validate();
        try (var c = connect(url)) {
            assertThat(number(c, "SELECT count(*) FROM flyway_agent_schema_history WHERE type='SQL' AND version::int<=17")).isEqualTo(9);
            for (String table : tables()) assertThat(text(c, "SELECT to_regclass('agent." + table + "')::text")).isEqualTo("agent." + table);
            assertThat(text(c, "SELECT to_regclass('agent.daily_grant_stops')::text")).isNull();
            assertThat(text(c, "SELECT to_regclass('agent.daily_request_links')::text")).isNull();
        }
    }
    @Test void emptyTo17CanApplyAllNineMigrationsAndValidate() throws Exception {
        String url = emptyDatabase(); main(url).migrate(); var migration = agent(url).target("17").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(9);
        assertThat(migration.info().current().getVersion()).isEqualTo(MigrationVersion.fromVersion("17"));
        assertThat(migration.migrate().migrationsExecuted).isZero(); migration.validate();
    }
    @Test void upgradePreservesAllPublishedChecksumsAndLegacyFactsIncludingV16Slots() throws Exception {
        String url = database16(); Map<String, String> before; List<String> checksums;
        try (var c = connect(url)) { legacy(c); before = legacyHistory(c); checksums = checksums(c); }
        assertThat(agent(url).target("17").load().migrate().migrationsExecuted).isOne();
        try (var c = connect(url)) {
            assertThat(checksums(c)).isEqualTo(checksums); assertThat(legacyHistory(c)).isEqualTo(before);
            assertThat(number(c, "SELECT count(*) FROM agent.live_template_request_slots")).isOne();
            for (String t : tables()) assertThat(number(c, "SELECT count(*) FROM agent." + t)).isZero();
        }
        agent(url).target("17").load().validate();
    }
    @Test void riskEnvelopeRejectsMissingNullOrMismatchedBindingsAndUnconfirmedInput() throws Exception {
        String url = database17();
        try (var c = connect(url)) {
            UUID p = project(c); var valid = envelope(); risk(c, p, UUID.randomUUID(), valid);
            for (String field : List.of("schemaVersion", "runtimeBindingDigest", "runtimeBinding", "expiresAt", "sourcesReviewed", "riskAccepted", "materialRightsAccepted")) {
                var missing = valid.deepCopy(); missing.remove(field); rejectsRisk(c, p, missing);
                var nil = valid.deepCopy(); nil.putNull(field); rejectsRisk(c, p, nil);
            }
            for (String field : List.of("ownerId", "deploymentId", "credentialVersion", "dataPolicyVersion")) {
                var missing = valid.deepCopy(); ((ObjectNode) missing.path("runtimeBinding")).remove(field); rejectsRisk(c, p, missing);
                var nil = valid.deepCopy(); ((ObjectNode) nil.path("runtimeBinding")).putNull(field); rejectsRisk(c, p, nil);
                var different = valid.deepCopy(); ((ObjectNode) different.path("runtimeBinding")).put(field, "dataPolicyVersion".equals(field) ? "other-policy" : UUID.randomUUID().toString()); rejectsRisk(c, p, different);
            }
            for (String confirmation : List.of("sourcesReviewed", "riskAccepted", "materialRightsAccepted")) {
                var no = valid.deepCopy(); no.put(confirmation, false); rejectsRisk(c, p, no);
            }
            var hash = valid.deepCopy(); hash.put("runtimeBindingDigest", OTHER); rejectsRisk(c, p, hash);
            var expiry = valid.deepCopy(); expiry.put("expiresAt", "2026-10-30T04:00:00Z"); rejectsRisk(c, p, expiry);
            assertThat(number(c, "SELECT count(*) FROM agent.daily_risk_acknowledgements")).isOne();
        }
    }
    @Test void riskScalarKeyHashPolicyAndTimeConstraintsRejectAndLeaveFactsUnchanged() throws Exception {
        String url = database17();
        try (var c = connect(url)) {
            UUID p = project(c); String valid = riskSql(p, UUID.randomUUID(), envelope());
            List<String> invalid = List.of(valid.replace("'risk-key'", "''"), valid.replace("'risk-key'", "'with space'"),
                valid.replace("'risk-key'", "'" + "x".repeat(129) + "'"), valid.replace(HASH, HASH.toUpperCase()),
                valid.replace(POLICY, "wrong-policy"), valid.replace("'" + EXPIRY + "'", "'2026-10-30T01:00:00Z'"),
                valid.replace("'" + EXPIRY + "'", "'2026-11-07T03:00:00Z'"));
            for (String sql : invalid) rejects(c, sql, "23514");
            assertThat(number(c, "SELECT count(*) FROM agent.daily_risk_acknowledgements")).isZero();
            execute(c, valid); assertThat(number(c, "SELECT count(*) FROM agent.daily_risk_acknowledgements")).isOne();
        }
    }
    @Test void grantCompositeProjectAndExactRiskBindingsAreEnforced() throws Exception {
        String url = database17();
        try (var c = connect(url)) {
            var f = fixture(c); String sql = grantSql(f, UUID.randomUUID(), "ARRAY['PR_SUMMARY']", 2, 700000);
            UUID otherProject = project(c);
            rejects(c, sql.replace("'" + f.project + "'", "'" + otherProject + "'"), "23503");
            for (String binding : List.of(OWNER.toString(), DEPLOYMENT.toString(), CREDENTIAL.toString(), HASH)) {
                rejects(c, sql.replace(binding, binding.equals(HASH) ? OTHER : UUID.randomUUID().toString()), "23514");
            }
            rejects(c, sql.replace("'2026-10-30T02:30:00Z'", "'2026-10-30T04:00:00Z'"), "23514");
            rejects(c, sql.replace("'2026-10-30T02:30:00Z'", "'2026-10-30T01:00:00Z'"), "23514");
            assertThat(number(c, "SELECT count(*) FROM agent.daily_grants")).isOne();
        }
    }
    @Test void grantPurposesAndAmountExtremaUseBigintAndRejectInvalidSets() throws Exception {
        String url = database17();
        try (var c = connect(url)) {
            var f = fixture(c);
            for (String purposes : List.of("ARRAY[]::text[]", "ARRAY['PR_SUMMARY','PR_SUMMARY']", "ARRAY['UNKNOWN']", "ARRAY[NULL]::text[]", "NULL"))
                rejects(c, grantSql(f, UUID.randomUUID(), purposes, 2, 700000), purposes.equals("NULL") ? "23502" : "23514");
            for (int attempts : new int[]{0, -1}) rejects(c, grantSql(f, UUID.randomUUID(), "ARRAY['PR_SUMMARY']", attempts, 316373), "23514");
            for (long cost : new long[]{-1, 0, 316372, 700001, Long.MAX_VALUE}) rejects(c, grantSql(f, UUID.randomUUID(), "ARRAY['PR_SUMMARY']", 2, cost), "23514");
            execute(c, grantSql(f, UUID.randomUUID(), "ARRAY['FINDING_EXPLANATION','PR_SUMMARY']", Integer.MAX_VALUE, (long) Integer.MAX_VALUE * 350000));
            execute(c, grantSql(f, UUID.randomUUID(), "ARRAY['PR_SUMMARY']", 1, 316373));
            assertThat(number(c, "SELECT count(*) FROM agent.daily_grants")).isEqualTo(3);
        }
    }
    @Test void bothApprovalAndBothRevocationHistoriesRejectEveryUpdateAndDelete() throws Exception {
        String url = database17();
        try (var c = connect(url)) {
            var f = fixture(c);
            execute(c, "INSERT INTO agent.daily_risk_revocations VALUES ('" + f.risk + "','" + OWNER + "','" + AT + "')");
            execute(c, "INSERT INTO agent.daily_grant_revocations VALUES ('" + f.grant + "','" + OWNER + "','" + AT + "')");
            for (String table : List.of("daily_risk_acknowledgements", "daily_grants", "daily_risk_revocations", "daily_grant_revocations")) {
                String before = snapshot(c, table); String column = table.contains("revocations") ? "revoked_at" : "expires_at";
                rejects(c, "UPDATE agent." + table + " SET " + column + "=" + column, "P0001");
                rejects(c, "DELETE FROM agent." + table, "P0001"); assertThat(snapshot(c, table)).isEqualTo(before);
            }
        }
    }
    @Test void settingsEnforceEnabledSelectedGrantAndCompositeIdentity() throws Exception {
        String url = database17();
        try (var c = connect(url)) {
            var f = fixture(c); UUID other = project(c);
            for (String values : List.of("true,0,NULL", "false,0,'" + f.grant + "'", "false,-1,NULL"))
                rejects(c, "INSERT INTO agent.daily_project_settings(project_id,enabled,revision,grant_id) VALUES ('" + f.project + "'," + values + ")", "23514");
            rejects(c, "INSERT INTO agent.daily_project_settings(project_id,enabled,grant_id) VALUES ('" + other + "',true,'" + f.grant + "')", "23503");
            execute(c, "INSERT INTO agent.daily_project_settings(project_id) VALUES ('" + f.project + "')");
            String before = snapshot(c, "daily_project_settings");
            rejects(c, "UPDATE agent.daily_project_settings SET enabled=true", "23514");
            assertThat(snapshot(c, "daily_project_settings")).isEqualTo(before);
        }
    }
    @Test void usageInsertAndUpdateCapsIdentityAndMonotonicAttemptsCannotBeBypassedOrOverflowed() throws Exception {
        String url = database17();
        try (var c = connect(url)) {
            var f = fixture(c); UUID other = project(c);
            for (String facts : List.of("-1,0,0", "3,0,0", "0,-1,0", "0,0,-1", "0,700001,0", "0,0,700001", "0,500000,200001", "0," + Long.MAX_VALUE + "," + Long.MAX_VALUE))
                rejects(c, usageSql(f, facts), "23514");
            rejects(c, usageSql(f, "0,0,0").replace(f.project.toString(), other.toString()), "23503");
            execute(c, usageSql(f, "1,316373,1")); String before = snapshot(c, "daily_grant_usage");
            var otherGrant = UUID.randomUUID(); execute(c, grantSql(f, otherGrant, "ARRAY['PR_SUMMARY']", 2, 700000));
            for (String change : List.of("attempts=0", "attempts=3", "reserved_microusd=700000", "spent_microusd=700000",
                    "reserved_microusd=" + Long.MAX_VALUE + ",spent_microusd=" + Long.MAX_VALUE,
                    "grant_id='" + otherGrant + "'", "project_id='" + other + "'")) {
                rejects(c, "UPDATE agent.daily_grant_usage SET " + change + " WHERE grant_id='" + f.grant + "'", "23514");
                assertThat(snapshot(c, "daily_grant_usage")).isEqualTo(before);
            }
            execute(c, "UPDATE agent.daily_grant_usage SET reserved_microusd=0,spent_microusd=316374 WHERE grant_id='" + f.grant + "'");
            assertThat(number(c, "SELECT attempts FROM agent.daily_grant_usage WHERE grant_id='" + f.grant + "'")).isOne();
        }
    }

    List<String> tables() { return List.of("daily_risk_acknowledgements", "daily_risk_revocations", "daily_grants", "daily_grant_revocations", "daily_project_settings", "daily_grant_usage"); }
    ObjectNode envelope() throws Exception {
        // 此 JSON 的来源日期明确为合成，且独立于动作时间；保持完整当前 1017/context 绑定。
        return (ObjectNode) mapper.readTree("""
            {"schemaVersion":"0.1.0","runtimeBindingDigest":"%s","expiresAt":"2026-10-30T03:00:00Z",
             "sourcesReviewed":true,"riskAccepted":true,"materialRightsAccepted":true,
             "runtimeBinding":{"deploymentId":"%s","ownerId":"%s","runtimeRevision":"%s","credentialVersion":"%s",
             "modelAlias":"deepseek-flash","mappingSnapshot":"DeepSeek-V4.1-Flash-1017","allowedResponseModels":["deepseek-flash"],
             "modelProtocolVersion":"responses-v1-restricted","explanationPromptVersion":"finding-explanation-deepseek-0.1.0",
             "summaryPromptVersion":"pr-summary-deepseek-0.1.0","outputSchemaVersion":"0.1.0",
             "priceCatalogVersion":"deepseek-flash-context-peak-usd-2026-10-15","dataPolicyVersion":"personal-selected-nonsensitive-0.1.0",
             "secretCheckRef":"synthetic-daily-secret","networkCheckRef":"synthetic-daily-network",
             "evidence":{"priceCatalogExpiresAt":"2026-10-31T02:00:00Z","sources":[
             {"kind":"TERMS","url":"https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html","version":"synthetic-daily-v1","checkedAt":"2026-10-30T01:00:00Z"},
             {"kind":"PRIVACY","url":"https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html","version":"synthetic-daily-v1","checkedAt":"2026-10-30T01:00:00Z"},
             {"kind":"CACHE","url":"https://api-docs.deepseek.com/guides/kv_cache/","version":"synthetic-daily-v1","checkedAt":"2026-10-30T01:00:00Z"},
             {"kind":"MODEL_PRICE","url":"https://api-docs.deepseek.com/quick_start/pricing/","version":"synthetic-daily-v1","checkedAt":"2026-10-30T01:00:00Z"},
             {"kind":"PROTOCOL","url":"https://api-docs.deepseek.com/guides/responses_api/","version":"synthetic-daily-v1","checkedAt":"2026-10-30T01:00:00Z"}]}}}
            """.formatted(HASH, DEPLOYMENT, OWNER, HASH, CREDENTIAL));
    }
    UUID project(Connection c) throws SQLException {
        UUID p = UUID.randomUUID(); execute(c, "INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES ('" + p + "','daily-" + p.toString().substring(0, 12) + "','Synthetic daily migration','" + AT + "','" + OWNER + "')"); return p;
    }
    Fixture fixture(Connection c) throws Exception {
        UUID p = project(c), r = UUID.randomUUID(), g = UUID.randomUUID(); risk(c, p, r, envelope());
        var f = new Fixture(p, r, g); execute(c, grantSql(f, g, "ARRAY['PR_SUMMARY']", 2, 700000)); return f;
    }
    void risk(Connection c, UUID p, UUID id, ObjectNode json) throws SQLException { execute(c, riskSql(p, id, json)); }
    String riskSql(UUID p, UUID id, ObjectNode json) {
        return "INSERT INTO agent.daily_risk_acknowledgements(id,project_id,deployment_id,owner_id,idempotency_key,input_digest,runtime_binding_digest,credential_version,data_policy_version,acknowledged_at,expires_at,acknowledgement) VALUES ('"
            + id + "','" + p + "','" + DEPLOYMENT + "','" + OWNER + "','risk-key','" + HASH + "','" + HASH + "','" + CREDENTIAL + "','" + POLICY + "','" + AT + "','" + EXPIRY + "','" + json.toString().replace("'", "''") + "'::jsonb)";
    }
    String grantSql(Fixture f, UUID id, String purposes, int attempts, long cost) {
        return "INSERT INTO agent.daily_grants(id,project_id,deployment_id,owner_id,risk_acknowledgement_id,idempotency_key,input_digest,runtime_binding_digest,credential_version,allowed_purposes,max_attempts,max_cost_microusd,approved_at,expires_at) VALUES ('"
            + id + "','" + f.project + "','" + DEPLOYMENT + "','" + OWNER + "','" + f.risk + "','" + id + "','" + HASH + "','" + HASH + "','" + CREDENTIAL + "'," + purposes + "," + attempts + "," + cost + ",'" + AT + "','2026-10-30T02:30:00Z')";
    }
    String usageSql(Fixture f, String facts) { return "INSERT INTO agent.daily_grant_usage(grant_id,project_id,attempts,reserved_microusd,spent_microusd) VALUES ('" + f.grant + "','" + f.project + "'," + facts + ")"; }
    void rejectsRisk(Connection c, UUID p, ObjectNode json) {
        // 不同键使 JSON 约束成为被测拒绝原因，而不是先触发幂等唯一键。
        rejects(c, riskSql(p, UUID.randomUUID(), json).replace("'risk-key'", "'" + UUID.randomUUID() + "'"), "23514");
    }
    void rejects(Connection c, String sql, String state) {
        assertThatThrownBy(() -> execute(c, sql)).isInstanceOf(SQLException.class)
            .satisfies(f -> assertThat(((SQLException) f).getSQLState()).isEqualTo(state));
    }
    void legacy(Connection c) throws SQLException {
        UUID p = project(c), ack = UUID.randomUUID(), batch = UUID.randomUUID(), request = UUID.randomUUID(), template = UUID.randomUUID();
        execute(c, "INSERT INTO agent.personal_enablements(id,project_id,deployment_id,approved_by,approved_at,expires_at,credential_version,acknowledgement) VALUES ('" + ack + "','" + p + "','" + DEPLOYMENT + "','" + OWNER + "','" + AT + "','" + EXPIRY + "','" + CREDENTIAL + "','{}')");
        execute(c, "INSERT INTO agent.live_batches(id,project_id,enablement_id,deployment_id,inventory_id,approved_by,approved_at,expires_at,manifest_sha256,manifest,max_requests,max_cost_microusd,cost_policy_version) VALUES ('" + batch + "','" + p + "','" + ack + "','" + DEPLOYMENT + "','" + p + "','" + OWNER + "','" + AT + "','" + EXPIRY + "','" + HASH + "','{}',2,700000,'deepseek-flash-context-peak-usd-2026-10-15')");
        execute(c, "INSERT INTO agent.requests(id,project_id,requester_id,idempotency_key,input_digest,trace_id,purpose,state,bindings,created_at,updated_at) VALUES ('" + request + "','" + p + "','" + OWNER + "','legacy','" + HASH + "','" + "c".repeat(32) + "','FINDING_EXPLANATION','RUNNING','{}','" + AT + "','" + AT + "')");
        execute(c, "INSERT INTO agent.live_request_links(request_id,project_id,batch_id,template_id,enablement_id,credential_version,settings_revision,created_at) VALUES ('" + request + "','" + p + "','" + batch + "','" + template + "','" + ack + "','" + CREDENTIAL + "',0,'" + AT + "')");
    }
    Map<String, String> legacyHistory(Connection c) throws SQLException {
        var values = new LinkedHashMap<String, String>();
        for (String t : List.of("requests", "personal_enablements", "enablement_revocations", "project_settings", "live_batches", "live_batch_usage", "live_batch_revocations", "live_budget_usage", "live_attempts", "live_outcomes", "live_request_links", "live_batch_stops", "live_template_request_slots")) values.put(t, snapshot(c, t)); return values;
    }
    List<String> checksums(Connection c) throws SQLException {
        var values = new ArrayList<String>();
        for (String history : List.of("flyway_schema_history", "flyway_agent_schema_history"))
            try (var st = c.createStatement(); var rs = st.executeQuery("SELECT version,checksum FROM " + history + " WHERE type='SQL' AND version::integer<=16 ORDER BY installed_rank")) {
                while (rs.next()) values.add(history + ":" + rs.getString(1) + ":" + rs.getInt(2));
            }
        return values;
    }
    String snapshot(Connection c, String table) throws SQLException { return text(c, "SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM agent." + table + " t"); }
    String database16() throws SQLException { String url = emptyDatabase(); main(url).migrate(); agent(url).target("16").load().migrate(); return url; }
    String database17() throws SQLException {
        String url = database16(); var migration = agent(url).target("17").load();
        assertThat(migration.migrate().migrationsExecuted).as("V17 must exist before constraint assertions").isOne(); return url;
    }
    String emptyDatabase() throws SQLException {
        String name = "archguard_daily_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = connect(POSTGRES.getJdbcUrl())) { execute(c, "CREATE DATABASE " + name); }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }
    Flyway main(String url) { return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load(); }
    FluentConfiguration agent(String url) { return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/agent-migration").defaultSchema("public").table("flyway_agent_schema_history").baselineOnMigrate(true).baselineVersion("8"); }
    Connection connect(String url) throws SQLException { return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); }
    void execute(Connection c, String sql) throws SQLException { try (var st = c.createStatement()) { st.setQueryTimeout(10); st.execute(sql); } }
    String text(Connection c, String sql) throws SQLException { try (var st = c.createStatement(); var rs = st.executeQuery(sql)) { assertThat(rs.next()).isTrue(); return rs.getString(1); } }
    long number(Connection c, String sql) throws SQLException { try (var st = c.createStatement(); var rs = st.executeQuery(sql)) { assertThat(rs.next()).isTrue(); return rs.getLong(1); } }
    record Fixture(UUID project, UUID risk, UUID grant) {}
}
