package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import({AgentPersonalEnablementIntegrationTest.Config.class, AgentLiveAccountingIntegrationTest.Config.class})
class AgentLiveAccountingIntegrationTest extends PostgresIntegrationTestSupport {
    static final UUID OWNER = UUID.fromString(AgentPersonalEnablementIntegrationTest.OWNER);
    static final UUID DEPLOYMENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final AtomicInteger DAY = new AtomicInteger();
    static final java.util.Set<String> INVENTORY = ConcurrentHashMap.newKeySet();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentEnablementOperations enablements;
    @Autowired AgentStore requests;
    @Autowired LiveAccountingOperations accounting;
    UUID project, acknowledgement, template;
    Instant now;

    @BeforeEach void prepare() throws Exception {
        var fixture = new AgentPersonalEnablementIntegrationTest();
        fixture.mvc = mvc; fixture.mapper = mapper; fixture.jdbc = jdbc; fixture.prepare(); project = fixture.project;
        now = AgentPersonalEnablementIntegrationTest.NOW.plusSeconds(DAY.incrementAndGet() * 86400L);
        AgentPersonalEnablementIntegrationTest.TIME.set(now);
        INVENTORY.clear();
        identity(OWNER);
        ObjectNode input = mapper.valueToTree(fixture.input());
        input.put("expiresAt", now.plusSeconds(86400).toString()).put("priceCatalogExpiresAt", now.plusSeconds(86400).toString());
        for (var source : input.path("sources")) ((ObjectNode) source).put("checkedAt", now.toString());
        acknowledgement = enablements.approve(project, mapper.treeToValue(input, PersonalEnablementInput.class)).id();
        enablements.update(project, 0, new AgentSettingsUpdate(true, acknowledgement));
        template = request(null, false);
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId"); }
    static void identity(UUID actor) {
        org.slf4j.MDC.put("traceId", "d".repeat(32));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor.toString(), null, List.of()));
    }
    UUID request(UUID batch, boolean live) {
        UUID id = UUID.randomUUID();
        var input = new AgentModelPort.ModelInput("FINDING_EXPLANATION", "0.1.0", "finding-explanation-0.1.0",
            "f1", "synthetic.rule", "0.1.0", "high", "Synthetic evidence", "synthetic.module", List.of(), List.of(), null);
        String digest = "a".repeat(64);
        if (live) {
            input = new AgentModelPort.ModelInput(input.purpose(), input.schemaVersion(), LiveAccountingOperations.EXPLANATION_PROMPT,
                input.findingRef(), input.ruleId(), input.ruleVersion(), input.severity(), input.message(), input.subject(), input.evidence(), input.documents(), input.findings());
            digest = accounting.requestDigest(project, batch, template);
        }
        var templateView = live ? requests.find(project, template).orElseThrow().view() : null;
        UUID scan = live ? templateView.bindings().scanJobId() : UUID.randomUUID();
        List<UUID> findings = live ? templateView.bindings().findingIds() : List.of(UUID.randomUUID());
        var bindings = new AgentRequestView.VersionBindings(scan, "b".repeat(64), null, findings, List.of(), input.promptVersion(),
            live ? LiveAccountingOperations.MODEL_PROFILE : "disabled-or-test-fake-0.1.0", "responses-v1-restricted",
            live ? "deepseek-flash" : "deterministic-fake-or-disabled", "0.1.0", live ? LiveCostPolicy.VERSION : "synthetic-0.1.0", digest);
        var view = new AgentRequestView(id, project, OWNER, "c".repeat(32), input.purpose(), live ? "RUNNING" : "FAILED", bindings, null,
            live ? null : new AgentRequestView.AgentFailure("MODEL_DISABLED", "Synthetic template"), null, now, now);
        assertThat(requests.insert(new AgentSnapshot(view, input, List.of()), id.toString())).isTrue();
        return id;
    }
    LiveBatchView batch(int count, long cost) {
        String digest = accounting.preview(project, List.of(template));
        INVENTORY.add(project + ":" + digest);
        return accounting.approve(project, "http://localhost:8083", false,
            new LiveBatchInput(acknowledgement, UUID.randomUUID(), List.of(template), digest, now.plusSeconds(3600), count, cost, true));
    }
    long amount(String scope, UUID id, String column) {
        return jdbc.sql("SELECT " + column + " FROM agent.live_budget_usage WHERE scope=:s AND scope_id=:id AND utc_day=:day")
            .param("s", scope).param("id", id).param("day", now.atZone(java.time.ZoneOffset.UTC).toLocalDate()).query(Long.class).optional().orElse(0L);
    }

    @Test void oneAttemptAndKnownSettlementAreIdempotentAndUseActualCacheTokens() throws Exception {
        var batch = batch(2, 8400);
        UUID id = request(batch.id(), true);
        var first = accounting.reserve(project, batch.id(), template, id);
        assertThat(first.newAttempt()).isTrue();
        assertThat(first.reservedMicrousd()).isEqualTo(4200);
        assertThat(accounting.reserve(project, batch.id(), template, id).newAttempt()).isFalse();
        var usage = new LiveTokenUsage(1000, 100, 500, 1100, 0);
        var result = accounting.settle(project, id, LiveCostPolicy.VERSION, usage);
        assertThat(result.state()).isEqualTo("SETTLED");
        assertThat(result.actualMicrousd()).isEqualTo(273);
        assertThat(accounting.settle(project, id, LiveCostPolicy.VERSION, usage)).isEqualTo(result);
        assertThat(accounting.reserve(project, batch.id(), template, id).newAttempt()).isFalse();
        assertThat(amount("PROJECT", project, "reserved_microusd")).isZero();
        assertThat(amount("PROJECT", project, "spent_microusd")).isEqualTo(273);
        assertThat(amount("DEPLOYMENT", DEPLOYMENT, "spent_microusd")).isEqualTo(273);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE project_id=:p").param("p", project).query(Integer.class).single()).isOne();
        String metadata = jdbc.sql("SELECT metadata::text FROM audit.audit_records WHERE project_id=:p AND action='agent.live.finished'")
            .param("p", project).query(String.class).single();
        assertThat(mapper.readTree(metadata).path("cachedInputTokens").asInt()).isEqualTo(500);
        assertThat(metadata).doesNotContain("Synthetic evidence");
    }

    @Test void databaseRejectsIncompleteSettlementWithoutWritingAnOutcome() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO agent.live_outcomes(request_id,state,usage,actual_microusd,created_at) VALUES (:id,'SETTLED',NULL,1,NOW())")
            .param("id", id).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO agent.live_outcomes(request_id,state,usage,actual_microusd,created_at) VALUES (:id,'SETTLED','{}',NULL,NOW())")
            .param("id", id).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_outcomes WHERE request_id=:id").param("id", id).query(Integer.class).single()).isZero();
        assertThat(amount("PROJECT", project, "reserved_microusd")).isEqualTo(4200);
    }

    @Test void approvalRejectsWrongDigestMissingConsentUnsafeOriginAndOutOfRangeLimits() {
        String hash = accounting.preview(project, List.of(template)); INVENTORY.add(project + ":" + hash);
        for (LiveBatchInput input : List.of(
                new LiveBatchInput(acknowledgement, UUID.randomUUID(), List.of(template), hash, now.plusSeconds(3600), 1, 4200, false),
                new LiveBatchInput(acknowledgement, UUID.randomUUID(), List.of(template), "f".repeat(64), now.plusSeconds(3600), 1, 4200, true),
                new LiveBatchInput(acknowledgement, UUID.randomUUID(), List.of(template), hash, now.plusSeconds(86401), 1, 4200, true),
                new LiveBatchInput(acknowledgement, UUID.randomUUID(), List.of(template), hash, now.plusSeconds(3600), 1, 8400, true))) {
            assertThatThrownBy(() -> accounting.approve(project, "http://localhost:8083", false, input)).isInstanceOf(AgentInvalidException.class);
        }
        var valid = new LiveBatchInput(acknowledgement, UUID.randomUUID(), List.of(template), hash, now.plusSeconds(3600), 1, 4200, true);
        assertThatThrownBy(() -> accounting.approve(project, "https://untrusted.invalid", true, valid))
            .isInstanceOf(io.github.aiarchguard.platform.agentcredential.CredentialFailure.class)
            .satisfies(failure -> assertThat(((io.github.aiarchguard.platform.agentcredential.CredentialFailure) failure).kind())
                .isEqualTo(io.github.aiarchguard.platform.agentcredential.CredentialFailure.Kind.DENIED));
        assertThatThrownBy(() -> accounting.preview(project, List.of(template, template))).isInstanceOf(AgentInvalidException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_batches WHERE project_id=:p").param("p", project).query(Integer.class).single()).isZero();
    }

    @Test void sourceWithdrawalAndPriceMismatchFailClosedWithoutRefund() {
        var batch = batch(2, 8400); UUID first = request(batch.id(), true), second = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, first);
        assertThat(accounting.settle(project, first, "unapproved-price", new LiveTokenUsage(1, 1, 0, 2, 0)).state()).isEqualTo("UNKNOWN");
        INVENTORY.clear();
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, second)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(amount("PROJECT", project, "reserved_microusd")).isEqualTo(4200);
    }

    @Test void concurrentSettlementsWriteExactlyOneOutcomeAndCharge() throws Exception {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true); accounting.reserve(project, batch.id(), template, id);
        var usage = new LiveTokenUsage(1000, 100, 500, 1100, 0);
        var outcomes = parallel(() -> accounting.settle(project, id, LiveCostPolicy.VERSION, usage),
            () -> accounting.settle(project, id, LiveCostPolicy.VERSION, usage));
        assertThat(outcomes.get(0)).isEqualTo(outcomes.get(1));
        assertThat(amount("PROJECT", project, "spent_microusd")).isEqualTo(273);
        assertThat(amount("DEPLOYMENT", DEPLOYMENT, "spent_microusd")).isEqualTo(273);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_outcomes WHERE request_id=:id").param("id", id).query(Integer.class).single()).isOne();
    }

    @Test void unknownUsageAndInvalidUsageKeepReservationAndCannotRefundOrRetry() {
        var batch = batch(2, 8400);
        UUID first = request(batch.id(), true), second = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, first);
        assertThat(accounting.unknown(project, first).state()).isEqualTo("UNKNOWN");
        accounting.reserve(project, batch.id(), template, second);
        assertThat(accounting.settle(project, second, LiveCostPolicy.VERSION, new LiveTokenUsage(8001, 0, 0, 8001, 0)).state()).isEqualTo("UNKNOWN");
        assertThat(amount("PROJECT", project, "reserved_microusd")).isEqualTo(8400);
        assertThat(accounting.settle(project, first, LiveCostPolicy.VERSION, new LiveTokenUsage(1, 1, 0, 2, 0)).state()).isEqualTo("UNKNOWN");
        assertThat(accounting.reserve(project, batch.id(), template, first).newAttempt()).isFalse();
    }

    @Test void expiryRotationDisabledSettingsAndRevocationDenyNewReservations() {
        var batch = batch(4, 16800);
        UUID id = request(batch.id(), true);
        enablements.update(project, 1, new AgentSettingsUpdate(false, null));
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, id)).isInstanceOf(AgentEnablementInvalidStateException.class);
        enablements.update(project, 2, new AgentSettingsUpdate(true, acknowledgement));
        accounting.revoke(project, batch.id(), "http://localhost:8083", false);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, id)).isInstanceOf(AgentEnablementInvalidStateException.class);
        var fresh = batch(1, 4200);
        AgentPersonalEnablementIntegrationTest.TIME.set(now.plusSeconds(3600));
        assertThatThrownBy(() -> accounting.reserve(project, fresh.id(), template, id)).isInstanceOf(AgentEnablementInvalidStateException.class);
        AgentPersonalEnablementIntegrationTest.TIME.set(now);
        AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.set(new io.github.aiarchguard.platform.agentcredential.CredentialStatus(true, UUID.randomUUID(), now));
        assertThatThrownBy(() -> accounting.reserve(project, fresh.id(), template, id)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE project_id=:p").param("p", project).query(Integer.class).single()).isZero();
    }

    @Test void unverifiedInventoryAndSyntheticProfileCannotObtainLiveAccounting() {
        String hash = accounting.preview(project, List.of(template));
        assertThatThrownBy(() -> accounting.approve(project, "http://localhost:8083", false,
            new LiveBatchInput(acknowledgement, UUID.randomUUID(), List.of(template), hash, now.plusSeconds(3600), 1, 4200, true)))
            .isInstanceOf(AgentUnavailableException.class);
        var batch = batch(1, 4200);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, template)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(accounting.get(project, batch.id()).manifestSha256()).isEqualTo(hash);
    }

    @Test void settledHistoryAndBatchHistoryCannotBeEditedOrDeleted() {
        var batch = batch(1, 4200);
        UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id); accounting.unknown(project, id);
        for (String table : List.of("live_batches", "live_attempts", "live_outcomes")) {
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM agent." + table).update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.sql("UPDATE agent.live_batches SET max_requests=20 WHERE id=:id").param("id", batch.id()).update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void batchRequestLimitAndCostLimitCannotBeReplenishedBySettlement() {
        var countLimited = batch(1, 4200);
        UUID first = request(countLimited.id(), true), second = request(countLimited.id(), true);
        accounting.reserve(project, countLimited.id(), template, first);
        accounting.settle(project, first, LiveCostPolicy.VERSION, new LiveTokenUsage(0, 0, 0, 0, 0));
        assertThatThrownBy(() -> accounting.reserve(project, countLimited.id(), template, second)).isInstanceOf(AgentQuotaExceededException.class);
        var costLimited = batch(2, 4200);
        UUID costFirst = request(costLimited.id(), true), denied = request(costLimited.id(), true);
        accounting.reserve(project, costLimited.id(), template, costFirst); accounting.unknown(project, costFirst);
        assertThatThrownBy(() -> accounting.reserve(project, costLimited.id(), template, denied)).isInstanceOf(AgentQuotaExceededException.class);
    }

    @Test void projectDailyLimitIncludesOutstandingReservations() {
        var batch = batch(2, 8400);
        seedBudget("PROJECT", project, 5_000_000 - 4200);
        UUID first = request(batch.id(), true), second = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, first);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, second)).isInstanceOf(AgentQuotaExceededException.class);
        assertThat(amount("PROJECT", project, "reserved_microusd")).isEqualTo(4200);
        assertThat(amount("DEPLOYMENT", DEPLOYMENT, "reserved_microusd")).isEqualTo(4200);
    }

    @Test void concurrentReplaysHaveExactlyOneNewAttempt() throws Exception {
        var batch = batch(2, 8400); UUID id = request(batch.id(), true);
        var result = parallel(() -> accounting.reserve(project, batch.id(), template, id), () -> accounting.reserve(project, batch.id(), template, id));
        assertThat(result.stream().filter(LiveAccountingOperations.Admission::newAttempt)).hasSize(1);
        assertThat(result.get(0).attemptId()).isEqualTo(result.get(1).attemptId());
        assertThat(amount("PROJECT", project, "reserved_microusd")).isEqualTo(4200);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.reserved'")
            .param("p", project).query(Integer.class).single()).isOne();
    }

    @Test void deploymentQuotaIsSerializedAcrossDifferentProjects() throws Exception {
        var firstBatch = batch(1, 4200); UUID firstProject = project, firstTemplate = template;
        UUID firstRequest = request(firstBatch.id(), true);
        project = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.projects(id,project_key,name,created_by,created_at) VALUES (:id,:key,'Synthetic quota',:a,NOW())")
            .param("id", project).param("key", "p" + project.toString().replace("-", "").substring(0, 12)).param("a", OWNER).update();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'MAINTAINER',NOW())").param("p", project).param("a", OWNER).update();
        var old = enablements.get(firstProject, acknowledgement);
        String original = jdbc.sql("SELECT acknowledgement::text FROM agent.personal_enablements WHERE id=:id").param("id", acknowledgement).query(String.class).single();
        acknowledgement = enablements.approve(project, mapper.readValue(original, PersonalEnablementInput.class)).id();
        enablements.update(project, 0, new AgentSettingsUpdate(true, acknowledgement));
        template = request(null, false); var secondBatch = batch(1, 4200); UUID secondRequest = request(secondBatch.id(), true);
        UUID secondProject = project, secondTemplate = template;
        seedBudget("DEPLOYMENT", DEPLOYMENT, 20_000_000 - 4200);
        var result = parallel(() -> quotaResult(firstProject, firstBatch.id(), firstTemplate, firstRequest),
            () -> quotaResult(secondProject, secondBatch.id(), secondTemplate, secondRequest));
        assertThat(result).containsExactlyInAnyOrder(true, false);
        assertThat(amount("DEPLOYMENT", DEPLOYMENT, "reserved_microusd")).isEqualTo(4200);
        assertThat(amount("PROJECT", firstProject, "reserved_microusd") + amount("PROJECT", secondProject, "reserved_microusd")).isEqualTo(4200);
        assertThat(old.credentialVersion()).isEqualTo(AgentPersonalEnablementIntegrationTest.CREDENTIAL);
    }

    @Test void auditFailuresRollBackReservationsAndSettlements() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set("agent.live.reserved");
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, id)).isInstanceOf(AgentUnavailableException.class);
        assertThat(amount("PROJECT", project, "reserved_microusd")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE request_id=:id").param("id", id).query(Integer.class).single()).isZero();
        accounting.reserve(project, batch.id(), template, id);
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set("agent.live.finished");
        assertThatThrownBy(() -> accounting.settle(project, id, LiveCostPolicy.VERSION, new LiveTokenUsage(1, 1, 0, 2, 0))).isInstanceOf(AgentUnavailableException.class);
        assertThat(amount("PROJECT", project, "reserved_microusd")).isEqualTo(4200);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_outcomes WHERE request_id=:id").param("id", id).query(Integer.class).single()).isZero();
        assertThat(accounting.settle(project, id, LiveCostPolicy.VERSION, new LiveTokenUsage(1, 1, 0, 2, 0)).actualMicrousd()).isEqualTo(2);
    }

    @Test void closingAfterAttemptAndUtcRolloverDoNotEraseIncurredCharge() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        accounting.revoke(project, batch.id(), "http://localhost:8083", false);
        enablements.revoke(project, acknowledgement);
        jdbc.sql("DELETE FROM project.project_members WHERE project_id=:p").param("p", project).update();
        AgentPersonalEnablementIntegrationTest.TIME.set(now.plusSeconds(86400));
        assertThat(accounting.settle(project, id, LiveCostPolicy.VERSION, new LiveTokenUsage(1, 1, 0, 2, 0)).state()).isEqualTo("SETTLED");
        assertThat(amount("PROJECT", project, "spent_microusd")).isEqualTo(2);
        assertThat(amount("PROJECT", project, "reserved_microusd")).isZero();
    }

    @Test void batchScopePayloadChangesAndDifferentActorFailClosed() throws Exception {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'VIEWER',NOW())")
            .param("p", project).param("a", other).update();
        identity(other);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, id)).isInstanceOf(io.github.aiarchguard.platform.project.ProjectPermissionDeniedException.class);
        assertThatThrownBy(() -> accounting.approve(project, "http://localhost:8083", false,
            new LiveBatchInput(acknowledgement, UUID.randomUUID(), List.of(template), batch.manifestSha256(), now.plusSeconds(3600), 1, 4200, true)))
            .isInstanceOf(io.github.aiarchguard.platform.project.ProjectPermissionDeniedException.class);
        identity(OWNER);
        // Rebind a separate synthetic request with changed projected data, not by rewriting immutable input.
        var original = requests.find(project, id).orElseThrow(); var input = original.input();
        var changed = new AgentModelPort.ModelInput(input.purpose(), input.schemaVersion(), input.promptVersion(), input.findingRef(), input.ruleId(),
            input.ruleVersion(), input.severity(), "Unapproved changed payload", input.subject(), input.evidence(), input.documents(), input.findings());
        var v = original.view(); UUID changedId = UUID.randomUUID();
        assertThat(requests.insert(new AgentSnapshot(new AgentRequestView(changedId, project, OWNER, v.traceId(), v.purpose(), "RUNNING", v.bindings(),
            null, null, null, now, now), changed, original.candidates()), changedId.toString())).isTrue();
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, changedId)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), UUID.randomUUID(), id)).isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> accounting.get(UUID.randomUUID(), batch.id())).isInstanceOf(io.github.aiarchguard.platform.project.ProjectNotFoundException.class);
    }

    void seedBudget(String scope, UUID scopeId, long spent) {
        jdbc.sql("INSERT INTO agent.live_budget_usage(scope,scope_id,utc_day,spent_microusd) VALUES (:s,:id,:day,:spent)")
            .param("s", scope).param("id", scopeId).param("day", now.atZone(java.time.ZoneOffset.UTC).toLocalDate()).param("spent", spent).update();
    }
    boolean quotaResult(UUID p, UUID b, UUID t, UUID r) {
        try { return accounting.reserve(p, b, t, r).newAttempt(); } catch (AgentQuotaExceededException exhausted) { return false; }
    }
    <T> List<T> parallel(java.util.concurrent.Callable<T> first, java.util.concurrent.Callable<T> second) throws Exception {
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CyclicBarrier(2);
        java.util.function.Function<java.util.concurrent.Callable<T>, java.util.concurrent.Callable<T>> scoped = task -> () -> {
            identity(OWNER);
            try { start.await(10, java.util.concurrent.TimeUnit.SECONDS); return task.call(); }
            finally { SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId"); }
        };
        try {
            var a = executor.submit(scoped.apply(first)); var b = executor.submit(scoped.apply(second));
            return List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
    }

    @TestConfiguration static class Config {
        @Bean @Primary SyntheticBatchInventory approvedSyntheticInventory() { return (project, inventory, hash) -> INVENTORY.contains(project + ":" + hash); }
    }
}
