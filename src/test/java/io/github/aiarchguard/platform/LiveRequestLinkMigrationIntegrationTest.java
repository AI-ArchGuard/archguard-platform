package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.internal.AgentSnapshot;
import io.github.aiarchguard.platform.agent.internal.AgentStore;
import io.github.aiarchguard.platform.agent.internal.AgentTransitions;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingOperations;
import io.github.aiarchguard.platform.agent.internal.LiveRequestStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class LiveRequestLinkMigrationIntegrationTest extends PostgresIntegrationTestSupport {
    // Each test gets a fixed, current microsecond snapshot. Historical QUEUED timestamps
    // were legitimately expired by recovery schedulers in cached shared-database contexts.
    private final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    @Autowired LiveRequestStore links;
    @Autowired AgentStore requests;
    @Autowired AgentTransitions transitions;
    @Autowired JdbcClient jdbc;

    @Test void emptyDatabaseMigratesThroughV14AndSecondMigrationDoesNothing() throws Exception {
        String url = newDatabase();
        assertThat(Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .load().migrate().migrationsExecuted).isEqualTo(8);
        var current = agentMigration(url).load();
        assertThat(current.migrate().migrationsExecuted).isEqualTo(6);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("14");
        assertThat(current.migrate().migrationsExecuted).isZero();
        assertThat(current.validateWithResult().validationSuccessful).isTrue();
        try (var connection = connect(url); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM agent.live_request_links")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getLong(1)).isZero();
        }
    }

    @Test void v13UpgradePreservesEveryHistoryAndDoesNotInventLinksForExistingAttempts() throws Exception {
        String url = newDatabase();
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        var previous = agentMigration(url).target(MigrationVersion.fromVersion("13")).load();
        assertThat(previous.migrate().migrationsExecuted).isEqualTo(5);
        try (var connection = connect(url)) {
            seedHistory(connection);
            Map<String, String> before = history(connection);
            List<String> checksums = checksums(connection);
            var current = agentMigration(url).load();
            assertThat(current.migrate().migrationsExecuted).isOne();
            assertThat(current.info().current().getVersion().getVersion()).isEqualTo("14");
            assertThat(current.migrate().migrationsExecuted).isZero();
            assertThat(current.validateWithResult().validationSuccessful).isTrue();
            assertThat(history(connection)).isEqualTo(before);
            assertThat(checksums(connection)).isEqualTo(checksums);
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT count(*) FROM agent.live_request_links")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
            }
            try (var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("UPDATE agent.requests SET trace_id='changed'"))
                    .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"));
                assertThatThrownBy(() -> statement.execute("UPDATE agent.live_outcomes SET actual_microusd=0"))
                    .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"));
                assertThatThrownBy(() -> statement.execute("DELETE FROM agent.live_batches"))
                    .isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("P0001"));
            }
            assertThat(history(connection)).isEqualTo(before);
        }
    }

    @Test void currentStoreRoundTripsOnlyWithinProjectAndDatabaseKeepsLinksImmutable() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        UUID request = newRequest(fixture.project());
        var link = link(fixture, request, fixture.template(), fixture.batch(), fixture.approval(), 7);
        assertThat(links.find(fixture.project(), request)).isEmpty();
        links.insert(link);
        assertThat(links.find(fixture.project(), request)).contains(link);
        assertThat(links.find(UUID.randomUUID(), request)).isEmpty();
        assertThat(links.find(fixture.project(), UUID.randomUUID())).isEmpty();
        assertThatThrownBy(() -> links.insert(link)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE agent.live_request_links SET settings_revision=8 WHERE request_id=:id")
            .param("id", request).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM agent.live_request_links WHERE request_id=:id")
            .param("id", request).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(links.find(fixture.project(), request)).contains(link);
    }

    @Test void sameProjectForeignKeysRejectCrossProjectAndUnknownResourcesWithoutWritingLinks() throws Exception {
        Fixture first, second;
        try (var connection = connect(POSTGRES.getJdbcUrl())) {
            first = seedHistory(connection); second = seedHistory(connection);
        }
        List<LiveRequestStore.Link> invalid = List.of(
            link(first, newRequest(second.project()), first.template(), first.batch(), first.approval(), 7),
            link(first, newRequest(first.project()), second.template(), first.batch(), first.approval(), 7),
            link(first, newRequest(first.project()), first.template(), second.batch(), first.approval(), 7),
            link(first, newRequest(first.project()), first.template(), first.batch(), second.approval(), 7),
            link(first, UUID.randomUUID(), first.template(), first.batch(), first.approval(), 7),
            link(first, newRequest(first.project()), UUID.randomUUID(), first.batch(), first.approval(), 7),
            link(first, newRequest(first.project()), first.template(), UUID.randomUUID(), first.approval(), 7),
            link(first, newRequest(first.project()), first.template(), first.batch(), UUID.randomUUID(), 7));
        for (var link : invalid) {
            assertThatThrownBy(() -> links.insert(link)).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("23503"));
            assertThat(links.find(first.project(), link.requestId())).isEmpty();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_request_links WHERE project_id=:p")
            .param("p", first.project()).query(Integer.class).single()).isZero();
    }

    @Test void databaseRejectsSelfTemplateAndNegativeRevisionWithoutChangingRequestHistory() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        UUID request = newRequest(fixture.project());
        for (var invalid : List.of(link(fixture, request, request, fixture.batch(), fixture.approval(), 7),
                link(fixture, request, fixture.template(), fixture.batch(), fixture.approval(), -1))) {
            assertThatThrownBy(() -> links.insert(invalid)).isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(failure -> assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("23514"));
        }
        assertThat(links.find(fixture.project(), request)).isEmpty();
        assertThat(jdbc.sql("SELECT state FROM agent.requests WHERE id=:id").param("id", request).query(String.class).single())
            .isEqualTo("QUEUED");
    }

    @Test void syntheticClaimCannotTakeLiveOrAssociatedWorkButStillClaimsNormalSyntheticWork() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        UUID live = routedRequest(fixture.project(), true), associated = routedRequest(fixture.project(), false), synthetic = routedRequest(fixture.project(), false);
        links.insert(link(fixture, associated, fixture.template(), fixture.batch(), fixture.approval(), 7));

        assertThat(requests.claim(live, NOW)).isFalse();
        assertThat(requests.claim(associated, NOW)).isFalse();
        assertThat(requests.claim(synthetic, NOW)).isTrue();
        assertThat(requests.find(fixture.project(), live).orElseThrow().view().state()).isEqualTo("QUEUED");
        assertThat(requests.find(fixture.project(), associated).orElseThrow().view().state()).isEqualTo("QUEUED");
        assertThat(requests.find(fixture.project(), synthetic).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThat(requests.claim(synthetic, NOW)).isFalse();
    }

    @Test void liveClaimTakesEachSupportedPurposeOnceWithoutCreatingAttemptOrChangingAnyBudget() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        String before = claimAccountingHistory(fixture);
        for (String purpose : List.of("FINDING_EXPLANATION", "PR_SUMMARY")) {
            UUID request = routedRequest(fixture.project(), true, purpose, Map.of(), Map.of());
            var association = link(fixture, request, fixture.template(), fixture.batch(), fixture.approval(), 7);
            links.insert(association);

            assertThat(links.claim(association, fixture.project(), NOW.plusSeconds(1))).isTrue();
            assertThat(links.claim(association, fixture.project(), NOW.plusSeconds(2))).isFalse();
            var claimed = requests.find(fixture.project(), request).orElseThrow().view();
            assertThat(claimed.state()).isEqualTo("RUNNING");
            assertThat(claimed.updatedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(links.find(fixture.project(), request)).contains(association);
            assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE request_id=:request")
                .param("request", request).query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT budget_day IS NULL AND reserved_microusd IS NULL FROM agent.requests WHERE id=:request")
                .param("request", request).query(Boolean.class).single()).isTrue();
        }
        assertThat(claimAccountingHistory(fixture)).isEqualTo(before);
    }

    @Test void concurrentLiveClaimsHaveExactlyOneWinnerAndPreserveAssociation() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        UUID request = routedRequest(fixture.project(), true);
        var association = link(fixture, request, fixture.template(), fixture.batch(), fixture.approval(), 7);
        links.insert(association);
        var ready = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            try {
                for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Claim fixture was not released");
                    return links.claim(association, fixture.project(), NOW.plusSeconds(1));
                }));
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally { release.countDown(); }
            int winners = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isOne();
        }
        assertThat(requests.find(fixture.project(), request).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThat(links.find(fixture.project(), request)).contains(association);
    }

    @Test void liveClaimRejectsUnlinkedLiveAndAssociatedSyntheticRequests() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        UUID unlinked = routedRequest(fixture.project(), true), synthetic = routedRequest(fixture.project(), false);
        var unlinkedAssociation = link(fixture, unlinked, fixture.template(), fixture.batch(), fixture.approval(), 7);
        var syntheticAssociation = link(fixture, synthetic, fixture.template(), fixture.batch(), fixture.approval(), 7);
        links.insert(syntheticAssociation);

        assertThat(links.claim(unlinkedAssociation, fixture.project(), NOW.plusSeconds(1))).isFalse();
        assertThat(links.claim(syntheticAssociation, fixture.project(), NOW.plusSeconds(1))).isFalse();
        assertThat(requests.find(fixture.project(), unlinked).orElseThrow().view().state()).isEqualTo("QUEUED");
        assertThat(requests.find(fixture.project(), synthetic).orElseThrow().view().state()).isEqualTo("QUEUED");
    }

    @Test void liveClaimRejectsEveryAlteredLinkFieldAndWrongRequesterWithoutChangingTheQueuedRequest() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        UUID request = routedRequest(fixture.project(), true);
        var association = link(fixture, request, fixture.template(), fixture.batch(), fixture.approval(), 7);
        links.insert(association);
        List<LiveRequestStore.Link> altered = List.of(
            new LiveRequestStore.Link(UUID.randomUUID(), association.projectId(), association.batchId(), association.templateId(), association.enablementId(), association.credentialVersion(), association.settingsRevision(), association.createdAt()),
            new LiveRequestStore.Link(association.requestId(), UUID.randomUUID(), association.batchId(), association.templateId(), association.enablementId(), association.credentialVersion(), association.settingsRevision(), association.createdAt()),
            new LiveRequestStore.Link(association.requestId(), association.projectId(), UUID.randomUUID(), association.templateId(), association.enablementId(), association.credentialVersion(), association.settingsRevision(), association.createdAt()),
            new LiveRequestStore.Link(association.requestId(), association.projectId(), association.batchId(), UUID.randomUUID(), association.enablementId(), association.credentialVersion(), association.settingsRevision(), association.createdAt()),
            new LiveRequestStore.Link(association.requestId(), association.projectId(), association.batchId(), association.templateId(), UUID.randomUUID(), association.credentialVersion(), association.settingsRevision(), association.createdAt()),
            new LiveRequestStore.Link(association.requestId(), association.projectId(), association.batchId(), association.templateId(), association.enablementId(), UUID.randomUUID(), association.settingsRevision(), association.createdAt()),
            new LiveRequestStore.Link(association.requestId(), association.projectId(), association.batchId(), association.templateId(), association.enablementId(), association.credentialVersion(), association.settingsRevision() + 1, association.createdAt()),
            new LiveRequestStore.Link(association.requestId(), association.projectId(), association.batchId(), association.templateId(), association.enablementId(), association.credentialVersion(), association.settingsRevision(), association.createdAt().plusNanos(1_000)));

        for (var invalid : altered) assertThat(links.claim(invalid, fixture.project(), NOW.plusSeconds(1)))
            .as("Altered association %s must not claim", invalid).isFalse();
        assertThat(links.claim(association, UUID.randomUUID(), NOW.plusSeconds(1))).isFalse();
        assertThat(requests.find(fixture.project(), request).orElseThrow().view().state()).isEqualTo("QUEUED");
        assertThat(links.find(fixture.project(), request)).contains(association);
        assertThat(links.claim(association, fixture.project(), NOW.plusSeconds(1))).isTrue();
    }

    @Test void liveClaimRejectsWrongFixedBindingsAndInputVersionsForBothPurposes() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        for (String purpose : List.of("FINDING_EXPLANATION", "PR_SUMMARY")) {
            var badBindings = Map.of("modelProfileVersion", "disabled-or-test-fake-0.1.0",
                "modelProtocolVersion", "unsupported-protocol", "modelId", "deterministic-fake-or-disabled",
                "priceCatalogVersion", "synthetic-0.1.0", "outputSchemaVersion", "9.9.9",
                "promptVersion", "FINDING_EXPLANATION".equals(purpose) ? LiveAccountingOperations.SUMMARY_PROMPT : LiveAccountingOperations.EXPLANATION_PROMPT);
            for (var bad : badBindings.entrySet()) {
                UUID request = routedRequest(fixture.project(), true, purpose, Map.of(bad.getKey(), bad.getValue()), Map.of());
                var association = link(fixture, request, fixture.template(), fixture.batch(), fixture.approval(), 7);
                links.insert(association);
                assertThat(links.claim(association, fixture.project(), NOW.plusSeconds(1)))
                    .as("%s binding %s must fail closed", purpose, bad.getKey()).isFalse();
                assertThat(requests.find(fixture.project(), request).orElseThrow().view().state()).isEqualTo("QUEUED");
            }
            for (var bad : Map.of("schemaVersion", "9.9.9", "purpose", "FINDING_EXPLANATION".equals(purpose) ? "PR_SUMMARY" : "FINDING_EXPLANATION",
                    "promptVersion", "FINDING_EXPLANATION".equals(purpose) ? LiveAccountingOperations.SUMMARY_PROMPT : LiveAccountingOperations.EXPLANATION_PROMPT).entrySet()) {
                UUID request = routedRequest(fixture.project(), true, purpose, Map.of(), Map.of(bad.getKey(), bad.getValue()));
                var association = link(fixture, request, fixture.template(), fixture.batch(), fixture.approval(), 7);
                links.insert(association);
                assertThat(links.claim(association, fixture.project(), NOW.plusSeconds(1)))
                    .as("%s input %s must fail closed", purpose, bad.getKey()).isFalse();
                assertThat(requests.find(fixture.project(), request).orElseThrow().view().state()).isEqualTo("QUEUED");
            }
        }
    }

    @Test void liveClaimCannotChangeEitherTerminalState() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        for (boolean success : List.of(false, true)) {
            UUID request = routedRequest(fixture.project(), true);
            var association = link(fixture, request, fixture.template(), fixture.batch(), fixture.approval(), 7);
            links.insert(association);
            assertThat(links.claim(association, fixture.project(), NOW.plusSeconds(1))).isTrue();
            AgentRequestView.AgentResult result = success ? new AgentRequestView.AgentResult("Synthetic", List.of("Synthetic rule"), List.of(), List.of(), List.of(), "INSUFFICIENT") : null;
            AgentRequestView.AgentFailure failure = success ? null : new AgentRequestView.AgentFailure("MODEL_TIMEOUT", "Synthetic timeout");
            assertThat(requests.finish(request, result, failure, null, NOW.plusSeconds(2))).isTrue();
            var terminal = requests.find(fixture.project(), request).orElseThrow();

            assertThat(links.claim(association, fixture.project(), NOW.plusSeconds(3))).isFalse();
            assertThat(requests.find(fixture.project(), request)).contains(terminal);
        }
    }

    @Test void generatedQueuedFixtureCanRecoverWithAuditAndWithoutChangingAnyBudget() throws Exception {
        Fixture fixture;
        try (var connection = connect(POSTGRES.getJdbcUrl())) { fixture = seedHistory(connection); }
        UUID request = newRequest(fixture.project());
        String liveBudgetBefore = jdbc.sql("""
            SELECT jsonb_agg(to_jsonb(b) ORDER BY scope)::text FROM agent.live_budget_usage b WHERE scope_id=:project
            """).param("project", fixture.project()).query(String.class).single();

        transitions.recoverExpired(requests.find(fixture.project(), request).orElseThrow(), NOW.plusSeconds(1));

        var recovered = requests.find(fixture.project(), request).orElseThrow().view();
        assertThat(recovered.state()).isEqualTo("FAILED");
        assertThat(recovered.failure().code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(jdbc.sql("""
            SELECT count(*) FROM audit.audit_records WHERE project_id=:project AND action='agent.request.failed'
                AND metadata->>'requestId'=:request AND metadata->>'failureCode'='MODEL_TIMEOUT'
                AND metadata->>'recovery'='true'
            """).param("project", fixture.project()).param("request", request.toString()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.budget_usage WHERE scope_id=:project")
            .param("project", fixture.project()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE request_id=:request")
            .param("request", request).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
            SELECT jsonb_agg(to_jsonb(b) ORDER BY scope)::text FROM agent.live_budget_usage b WHERE scope_id=:project
            """).param("project", fixture.project()).query(String.class).single()).isEqualTo(liveBudgetBefore);
    }

    private UUID routedRequest(UUID project, boolean live) {
        return routedRequest(project, live, "FINDING_EXPLANATION", Map.of(), Map.of());
    }

    private UUID routedRequest(UUID project, boolean live, String purpose, Map<String, String> bindingOverrides, Map<String, String> inputOverrides) {
        UUID request = UUID.randomUUID();
        String prompt = "PR_SUMMARY".equals(purpose)
            ? live ? LiveAccountingOperations.SUMMARY_PROMPT : "pr-summary-0.1.0"
            : live ? LiveAccountingOperations.EXPLANATION_PROMPT : "finding-explanation-0.1.0";
        var bindings = new AgentRequestView.VersionBindings(UUID.randomUUID(), "a".repeat(64), null, List.of(UUID.randomUUID()),
            List.of(), bindingOverrides.getOrDefault("promptVersion", prompt), bindingOverrides.getOrDefault("modelProfileVersion", live ? LiveAccountingOperations.MODEL_PROFILE : "disabled-or-test-fake-0.1.0"),
            bindingOverrides.getOrDefault("modelProtocolVersion", "responses-v1-restricted"), bindingOverrides.getOrDefault("modelId", live ? "deepseek-flash" : "deterministic-fake-or-disabled"),
            bindingOverrides.getOrDefault("outputSchemaVersion", "0.1.0"), bindingOverrides.getOrDefault("priceCatalogVersion", live ? LiveCostPolicy.VERSION : "synthetic-0.1.0"), "b".repeat(64));
        var input = new AgentModelPort.ModelInput(inputOverrides.getOrDefault("purpose", purpose), inputOverrides.getOrDefault("schemaVersion", "0.1.0"),
            inputOverrides.getOrDefault("promptVersion", prompt), "c-" + UUID.randomUUID(),
            "synthetic.rule", "0.1.0", "high", "Synthetic migration fixture", "synthetic.module", List.of(), List.of(), null);
        var view = new AgentRequestView(request, project, project, "c".repeat(32), purpose,
            "QUEUED", bindings, null, null, null, NOW, NOW);
        assertThat(requests.insert(new AgentSnapshot(view, input, List.of()), request.toString())).isTrue();
        return request;
    }

    private String claimAccountingHistory(Fixture fixture) {
        return jdbc.sql("""
            SELECT jsonb_build_object(
                'synthetic', (SELECT COALESCE(jsonb_agg(to_jsonb(b) ORDER BY scope),'[]'::jsonb) FROM agent.budget_usage b WHERE scope_id=:project),
                'live', (SELECT COALESCE(jsonb_agg(to_jsonb(b) ORDER BY scope),'[]'::jsonb) FROM agent.live_budget_usage b WHERE scope_id=:project),
                'batch', (SELECT to_jsonb(b) FROM agent.live_batch_usage b WHERE batch_id=:batch),
                'attempts', (SELECT count(*) FROM agent.live_attempts WHERE project_id=:project))::text
            """).param("project", fixture.project()).param("batch", fixture.batch()).query(String.class).single();
    }

    private LiveRequestStore.Link link(Fixture fixture, UUID request, UUID template, UUID batch, UUID approval, long revision) {
        return new LiveRequestStore.Link(request, fixture.project(), batch, template, approval, fixture.credential(), revision, NOW);
    }

    private UUID newRequest(UUID project) {
        return routedRequest(project, false);
    }

    private Fixture seedHistory(Connection connection) throws SQLException {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID settled = UUID.randomUUID(), unknown = UUID.randomUUID();
        try (var statement = connection.createStatement()) {
            String project = fixture.project().toString();
            statement.execute("INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES ('" + project
                + "','link-" + project.replace("-", "").substring(0, 12) + "','Synthetic migration',NOW(),'" + project + "')");
            for (UUID request : List.of(fixture.template(), settled, unknown)) {
                boolean success = request.equals(settled);
                statement.execute("INSERT INTO agent.requests(id,project_id,requester_id,idempotency_key,input_digest,trace_id,purpose,state,bindings,"
                    + (success ? "result" : "failure") + ",created_at,updated_at) VALUES ('" + request + "','" + project + "','" + project
                    + "','" + request + "','" + "a".repeat(64) + "','" + "b".repeat(32) + "','FINDING_EXPLANATION','"
                    + (success ? "SUCCEEDED" : "FAILED") + "','{}','{}',NOW(),NOW())");
            }
            statement.execute("INSERT INTO agent.personal_enablements(id,project_id,deployment_id,approved_by,approved_at,expires_at,credential_version,acknowledgement) VALUES ('"
                + fixture.approval() + "','" + project + "','" + project + "','" + project + "',NOW(),NOW()+INTERVAL '1 day','" + fixture.credential() + "','{}')");
            statement.execute("INSERT INTO agent.project_settings(project_id,enabled,revision,enablement_id) VALUES ('" + project + "',true,7,'" + fixture.approval() + "')");
            statement.execute("INSERT INTO agent.live_batches(id,project_id,enablement_id,deployment_id,inventory_id,approved_by,approved_at,expires_at,manifest_sha256,manifest,max_requests,max_cost_microusd,inventory_proof) VALUES ('"
                + fixture.batch() + "','" + project + "','" + fixture.approval() + "','" + project + "','" + project + "','" + project
                + "',NOW(),NOW()+INTERVAL '1 hour','" + "c".repeat(64) + "','{}',2,8400,'{\"schemaVersion\":\"0.1.0\"}')");
            statement.execute("INSERT INTO agent.live_batch_usage(batch_id,reserved_microusd,spent_microusd,attempts) VALUES ('" + fixture.batch() + "',4200,2,2)");
            for (String scope : List.of("PROJECT", "DEPLOYMENT")) {
                statement.execute("INSERT INTO agent.live_budget_usage(scope,scope_id,utc_day,reserved_microusd,spent_microusd) VALUES ('" + scope + "','" + project + "',CURRENT_DATE,4200,2)");
            }
            for (UUID request : List.of(settled, unknown)) {
                statement.execute("INSERT INTO agent.live_attempts(request_id,project_id,batch_id,template_id,attempt_id,deployment_id,enablement_id,credential_version,settings_revision,utc_day,price_version,reserved_microusd,created_at) VALUES ('"
                    + request + "','" + project + "','" + fixture.batch() + "','" + fixture.template() + "','" + UUID.randomUUID() + "','" + project
                    + "','" + fixture.approval() + "','" + fixture.credential() + "',7,CURRENT_DATE,'synthetic-price',4200,NOW())");
                boolean known = request.equals(settled);
                statement.execute("INSERT INTO agent.live_outcomes(request_id,state,usage,actual_microusd,created_at) VALUES ('" + request
                    + "','" + (known ? "SETTLED" : "UNKNOWN") + "'," + (known ? "'{\"inputTokens\":1,\"outputTokens\":1}',2" : "NULL,NULL") + ",NOW())");
            }
        }
        return fixture;
    }

    private Map<String, String> history(Connection connection) throws SQLException {
        Map<String, String> snapshots = new LinkedHashMap<>();
        for (String table : List.of("requests", "personal_enablements", "project_settings", "live_batches", "live_batch_usage",
                "live_budget_usage", "live_attempts", "live_outcomes")) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT COALESCE(jsonb_agg(to_jsonb(h) ORDER BY to_jsonb(h)::text),'[]'::jsonb)::text FROM agent." + table + " h")) {
                assertThat(rows.next()).isTrue(); snapshots.put(table, rows.getString(1));
            }
        }
        return snapshots;
    }

    private List<String> checksums(Connection connection) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT version,checksum FROM flyway_agent_schema_history WHERE type='SQL' AND version<>'14' ORDER BY installed_rank")) {
            while (rows.next()) values.add(rows.getString(1) + ":" + rows.getInt(2));
        }
        return values;
    }

    private String newDatabase() throws SQLException {
        String database = "archguard_request_link_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect(POSTGRES.getJdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration agentMigration(String url) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/agent-migration").defaultSchema("public").table("flyway_agent_schema_history")
            .baselineOnMigrate(true).baselineVersion("8").target(MigrationVersion.fromVersion("14"));
    }

    private record Fixture(UUID project, UUID approval, UUID batch, UUID template, UUID credential) { }
}
