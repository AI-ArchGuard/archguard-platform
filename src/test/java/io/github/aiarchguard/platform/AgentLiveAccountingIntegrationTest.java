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
import javax.sql.DataSource;
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

/**
 * 使用隔离 PostgreSQL 验证 live 三层预算、唯一 attempt、发送前复核与不可变结算。时间、来源和凭据元数据由夹具控制；这些账本操作本身不访问模型或真实 Key。
 */
@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import({AgentPersonalEnablementIntegrationTest.Config.class, AgentLiveAccountingIntegrationTest.Config.class})
class AgentLiveAccountingIntegrationTest extends AgentLiveDatabaseIntegrationTestSupport {
    static final UUID OWNER = UUID.fromString(AgentPersonalEnablementIntegrationTest.OWNER);
    static final UUID DEPLOYMENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final AtomicInteger DAY = new AtomicInteger();
    static final java.util.Set<String> INVENTORY = ConcurrentHashMap.newKeySet();
    static final java.util.concurrent.atomic.AtomicReference<String> INVENTORY_SHA = new java.util.concurrent.atomic.AtomicReference<>("e".repeat(64));
    static final java.util.concurrent.atomic.AtomicReference<Instant> INVENTORY_REVIEWED_AT = new java.util.concurrent.atomic.AtomicReference<>();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentEnablementOperations enablements;
    @Autowired AgentStore requests;
    @Autowired LiveRequestStore links;
    @Autowired LiveAccountingOperations accounting;
    @Autowired LiveAccountingStore ledger;
    @Autowired AgentTransitions transitions;
    @Autowired DataSource dataSource;
    UUID project, acknowledgement, template;
    Instant now;

    @BeforeEach void prepare() throws Exception {
        var fixture = new AgentPersonalEnablementIntegrationTest();
        fixture.mvc = mvc; fixture.mapper = mapper; fixture.jdbc = jdbc; fixture.prepare(); project = fixture.project;
        now = AgentPersonalEnablementIntegrationTest.NOW.plusSeconds(DAY.incrementAndGet() * 86400L);
        AgentPersonalEnablementIntegrationTest.TIME.set(now);
        INVENTORY.clear();
        INVENTORY_SHA.set("e".repeat(64));
        INVENTORY_REVIEWED_AT.set(now);
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
    // 以下夹具显式区分旧合成模板与 live 请求，并可选择是否创建关联来覆盖历史缺失场景。
    UUID request(UUID batch, boolean live) {
        return request(batch, live, true);
    }
    UUID request(UUID batch, boolean live, boolean linked) {
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
        if (live && linked) links.insert(new LiveRequestStore.Link(id, project, batch, template, acknowledgement,
            AgentPersonalEnablementIntegrationTest.CREDENTIAL, enablements.settings(project).revision(), now));
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

    @Test void fixedClockRecoveryCannotShareSystemClockDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog())
                .as("A future test clock must not expire wall-clock requests in another cached context")
                .isNotEqualTo(PostgresIntegrationTestSupport.POSTGRES.getDatabaseName());
        }
        for (Class<?> test : List.of(AgentPersonalEnablementIntegrationTest.class,
                AgentBatchApiIntegrationTest.class, AgentLiveAccountingIntegrationTest.class,
                AgentLiveAdmissionWaitIntegrationTest.class, AgentLiveDispatchIntegrationTest.class,
                AgentLiveRequestCreationIntegrationTest.class, AgentContextAccountingIntegrationTest.class,
                AgentLiveDatabaseAccountingIntegrationTest.class, AgentLiveResultIntegrationTest.class)) {
            assertThat(AgentLiveDatabaseIntegrationTestSupport.class.isAssignableFrom(test))
                .as("%s uses the shared mutable clock and must use its separate database", test.getSimpleName())
                .isTrue();
        }
    }

    // 重复预留或结算不能重复记账，实际费用应使用可信缓存命中用量。
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

    // 快照形状不足以授权，必须有精确关联且设置版本仍有效。
    @Test void unlinkedLiveSnapshotCannotReserveAndStaleCreationRevisionCannotBeReenabled() {
        var batch = batch(2, 8400); UUID unlinked = request(batch.id(), true, false), linked = request(batch.id(), true);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, unlinked)).isInstanceOf(AgentEnablementInvalidStateException.class);
        enablements.update(project, 1, new AgentSettingsUpdate(false, null));
        enablements.update(project, 2, new AgentSettingsUpdate(true, acknowledgement));
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, linked)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(ledger.attempt(project, unlinked)).isEmpty(); assertThat(ledger.attempt(project, linked)).isEmpty();
        assertThat(amount("PROJECT", project, "reserved_microusd")).isZero();
    }

    // 历史 attempt 可读和可结算，不等于重新获得发送许可。
    @Test void legacyUnlinkedAttemptCanReplayAndSettleButCannotSend() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true, false);
        var day = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        ledger.lockBudget("DEPLOYMENT", DEPLOYMENT, day); ledger.lockBudget("PROJECT", project, day);
        ledger.reserveBudget("DEPLOYMENT", DEPLOYMENT, day, 4200); ledger.reserveBudget("PROJECT", project, day, 4200);
        ledger.reserveBatch(batch.id(), 4200);
        ledger.insertAttempt(new LiveAccountingStore.Attempt(id, project, batch.id(), template, UUID.randomUUID(), DEPLOYMENT, day,
            LiveCostPolicy.VERSION, 4200, null, null), acknowledgement, AgentPersonalEnablementIntegrationTest.CREDENTIAL, 1, now);
        assertThat(accounting.reserve(project, batch.id(), template, id).newAttempt()).isFalse();
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(accounting.settle(project, id, LiveCostPolicy.VERSION, new LiveTokenUsage(1, 1, 0, 2, 0)).actualMicrousd()).isEqualTo(2);
        assertThat(links.find(project, id)).isEmpty();
        assertThat(amount("PROJECT", project, "spent_microusd")).isEqualTo(2);
    }

    @Test void linkBatchTemplateEnablementAndCredentialMustMatchTheFrozenRequest() throws Exception {
        var batch = batch(4, 16800); var other = batch(1, 4200);
        UUID otherTemplate = request(null, false);
        String acknowledged = jdbc.sql("SELECT acknowledgement::text FROM agent.personal_enablements WHERE id=:id")
            .param("id", acknowledgement).query(String.class).single();
        UUID otherApproval = enablements.approve(project, mapper.readValue(acknowledged, PersonalEnablementInput.class)).id();
        for (int mismatch = 0; mismatch < 4; mismatch++) {
            UUID id = request(batch.id(), true, false);
            links.insert(new LiveRequestStore.Link(id, project, mismatch == 0 ? other.id() : batch.id(),
                mismatch == 1 ? otherTemplate : template, mismatch == 2 ? otherApproval : acknowledgement,
                mismatch == 3 ? UUID.randomUUID() : AgentPersonalEnablementIntegrationTest.CREDENTIAL, 1, now));
            assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, id)).isInstanceOf(AgentEnablementInvalidStateException.class);
            assertThat(ledger.attempt(project, id)).isEmpty();
        }
        assertThat(amount("PROJECT", project, "reserved_microusd")).isZero();
    }

    // 发送复核须存在预留，并返回冻结版本而不再次扣费。
    @Test void sendCheckRequiresReservationAndReturnsItsFrozenCredentialWithoutChargingAgain() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(ledger.attempt(project, id)).isEmpty();
        var admission = accounting.reserve(project, batch.id(), template, id);
        var approval = accounting.checkBeforeSend(project, batch.id(), template, id);
        assertThat(approval.credentialVersion()).isEqualTo(AgentPersonalEnablementIntegrationTest.CREDENTIAL);
        assertThat(approval.allowedResponseModels()).containsExactly("deepseek-flash");
        assertThat(accounting.checkBeforeSend(project, batch.id(), template, id)).isEqualTo(approval);
        assertThat(ledger.attempt(project, id).orElseThrow().attemptId()).isEqualTo(admission.attemptId());
        assertThat(ledger.reservationBinding(project, id)).isEqualTo(new LiveAccountingStore.ReservationBinding(
            acknowledgement, AgentPersonalEnablementIntegrationTest.CREDENTIAL, 1));
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(1);
    }

    @Test void revokedBatchCannotSendAnAlreadyReservedAttempt() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        accounting.revoke(project, batch.id(), "http://localhost:8083", false);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(accounting.reserve(project, batch.id(), template, id).newAttempt()).isFalse();
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(1);
    }

    @Test void revokedPersonalApprovalCannotSendAnAlreadyReservedAttempt() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        enablements.revoke(project, acknowledgement);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(1);
    }

    @Test void settingsRevisionChangeCannotBeUndoneByReenablingTheSameApproval() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        enablements.update(project, 1, new AgentSettingsUpdate(false, null));
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        var enabled = enablements.update(project, 2, new AgentSettingsUpdate(true, acknowledgement));
        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.enablementId()).isEqualTo(acknowledgement);
        assertThat(enabled.revision()).isEqualTo(3);
        assertThat(ledger.reservationBinding(project, id).settingsRevision()).isEqualTo(1);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(2);
    }

    // 预留后的换 Key 或删除也必须阻止新发送。
    @Test void credentialRotationAndClearingEachRejectReservedSendChecks() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.set(
            new io.github.aiarchguard.platform.agentcredential.CredentialStatus(true, UUID.randomUUID(), now));
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        // Restore only the fake metadata to isolate clearing from the preceding version mismatch.
        AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.set(new io.github.aiarchguard.platform.agentcredential.CredentialStatus(
            true, AgentPersonalEnablementIntegrationTest.CREDENTIAL, now));
        assertThat(accounting.checkBeforeSend(project, batch.id(), template, id).credentialVersion())
            .isEqualTo(AgentPersonalEnablementIntegrationTest.CREDENTIAL);
        AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.set(io.github.aiarchguard.platform.agentcredential.CredentialStatus.absent());
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(2);
    }

    // 来源撤回影响当前准入，但不能改写历史批准证明。
    @Test void withdrawnOrChangedInventoryProofRejectsReservedSendWithoutRewritingProof() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        var frozenProof = ledger.batch(project, batch.id()).orElseThrow().inventoryProof();
        INVENTORY.clear();
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        INVENTORY.add(project + ":" + batch.manifestSha256());
        assertThat(accounting.checkBeforeSend(project, batch.id(), template, id).credentialVersion())
            .isEqualTo(AgentPersonalEnablementIntegrationTest.CREDENTIAL);
        INVENTORY_SHA.set("9".repeat(64));
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(ledger.batch(project, batch.id()).orElseThrow().inventoryProof()).isEqualTo(frozenProof);
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(2);
    }

    @Test void expiredBatchCannotSendAnAlreadyReservedAttempt() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        AgentPersonalEnablementIntegrationTest.TIME.set(batch.expiresAt());
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(1);
    }

    // 费用按 UTC 日绑定，批准有效也不能跨日沿用旧发送许可。
    @Test void utcRolloverRejectsSendEvenWhileBatchApprovalAndProofRemainValid() {
        now = now.atZone(java.time.ZoneOffset.UTC).toLocalDate().atTime(23, 59, 50).toInstant(java.time.ZoneOffset.UTC);
        AgentPersonalEnablementIntegrationTest.TIME.set(now);
        INVENTORY_REVIEWED_AT.set(now);
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        Instant nextDay = now.plusSeconds(20);
        AgentPersonalEnablementIntegrationTest.TIME.set(nextDay);
        assertThat(batch.expiresAt()).isAfter(nextDay);
        assertThat(enablements.get(project, acknowledgement).expiresAt()).isAfter(nextDay);
        assertThat(ledger.batch(project, batch.id()).orElseThrow().inventoryProof().expiresAt()).isAfter(nextDay);
        assertThat(requests.find(project, id).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertPendingReservation(batch.id(), id);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_budget_usage WHERE scope='PROJECT' AND scope_id=:p AND utc_day=:day")
            .param("p", project).param("day", nextDay.atZone(java.time.ZoneOffset.UTC).toLocalDate()).query(Integer.class).single()).isZero();
        assertSendRejectionCount(1);
    }

    // 请求终态阻止发送，但已发生用量仍能独立入账。
    @Test void recoveredTerminalRequestCannotSendButItsIncurredUsageCanStillSettle() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        AgentPersonalEnablementIntegrationTest.TIME.set(now.plusSeconds(61));
        transitions.recoverExpired(requests.find(project, id).orElseThrow(), now.plusSeconds(1));
        var recovered = requests.find(project, id).orElseThrow().view();
        assertThat(recovered.state()).isEqualTo("FAILED");
        assertThat(recovered.failure().code()).isEqualTo("INTERNAL_ERROR");
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertPendingReservation(batch.id(), id);
        assertThat(accounting.settle(project, id, LiveCostPolicy.VERSION, new LiveTokenUsage(1, 1, 0, 2, 0)).actualMicrousd()).isEqualTo(2);
        assertThat(requests.find(project, id).orElseThrow().view()).isEqualTo(recovered);
        assertThat(amount("PROJECT", project, "reserved_microusd")).isZero();
        assertThat(amount("PROJECT", project, "spent_microusd")).isEqualTo(2);
        assertSendRejectionCount(1);
    }

    @Test void knownAndUnknownOutcomesEachDenyResendEvenIfRequestIsStillRunning() {
        var batch = batch(2, 8400); UUID unknown = request(batch.id(), true), settled = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, unknown);
        accounting.reserve(project, batch.id(), template, settled);
        accounting.unknown(project, unknown);
        accounting.settle(project, settled, LiveCostPolicy.VERSION, new LiveTokenUsage(1, 1, 0, 2, 0));
        for (UUID id : List.of(unknown, settled)) {
            assertThat(requests.find(project, id).orElseThrow().view().state()).isEqualTo("RUNNING");
            assertThat(accounting.reserve(project, batch.id(), template, id).newAttempt()).isFalse();
            assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
                .isInstanceOf(AgentEnablementInvalidStateException.class);
        }
        assertThat(ledger.attempt(project, unknown).orElseThrow().outcome()).isEqualTo("UNKNOWN");
        assertThat(ledger.attempt(project, settled).orElseThrow().outcome()).isEqualTo("SETTLED");
        assertThat(amount("PROJECT", project, "reserved_microusd")).isEqualTo(4200);
        assertThat(amount("PROJECT", project, "spent_microusd")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE project_id=:p").param("p", project).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_outcomes o JOIN agent.live_attempts a USING(request_id) WHERE a.project_id=:p")
            .param("p", project).query(Integer.class).single()).isEqualTo(2);
        assertSendRejectionCount(2);
    }

    @Test void sendCheckRejectsWrongProjectRequestBatchAndTemplateBindings() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        var differentBatch = batch(1, 4200);
        UUID unreservedRequest = request(batch.id(), true), differentTemplate = request(null, false);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, differentBatch.id(), template, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), differentTemplate, id))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, unreservedRequest))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, UUID.randomUUID()))
            .isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, UUID.randomUUID(), template, id))
            .isInstanceOf(AgentNotFoundException.class);
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.projects(id,project_key,name,created_by,created_at) VALUES (:id,:key,'Synthetic binding',:a,NOW())")
            .param("id", otherProject).param("key", "p" + otherProject.toString().replace("-", "").substring(0, 12)).param("a", OWNER).update();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'MAINTAINER',NOW())")
            .param("p", otherProject).param("a", OWNER).update();
        assertThatThrownBy(() -> accounting.checkBeforeSend(otherProject, batch.id(), template, id))
            .isInstanceOf(AgentNotFoundException.class);
        assertThat(ledger.attempt(otherProject, id)).isEmpty();
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(5);
    }

    @Test void anotherViewerAndRemovedMembershipCannotSendTheOwnersReservedAttempt() {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'VIEWER',NOW())")
            .param("p", project).param("a", other).update();
        identity(other);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(io.github.aiarchguard.platform.project.ProjectPermissionDeniedException.class);
        identity(OWNER);
        jdbc.sql("DELETE FROM project.project_members WHERE project_id=:p AND actor_id=:a").param("p", project).param("a", OWNER).update();
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, batch.id(), template, id))
            .isInstanceOf(io.github.aiarchguard.platform.project.ProjectNotFoundException.class);
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(2);
    }

    // 锁后重读当前请求，不能把等待前状态当成有效发送证据。
    @Test void sendCheckRereadsTheRequestAfterAnActualPostgresLockWait() throws Exception {
        var batch = batch(1, 4200); UUID id = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, id);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                int blockerPid;
                try (var statement = blocker.createStatement()) {
                    statement.setQueryTimeout(10);
                    try (var result = statement.executeQuery("SELECT pg_backend_pid()")) {
                        assertThat(result.next()).isTrue(); blockerPid = result.getInt(1);
                    }
                }
                try (var statement = blocker.prepareStatement("SELECT batch_id FROM agent.live_batch_usage WHERE batch_id=? FOR UPDATE")) {
                    statement.setQueryTimeout(10); statement.setObject(1, batch.id());
                    try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
                }
                var sendCheck = executor.submit(() -> {
                    identity(OWNER);
                    try { accounting.checkBeforeSend(project, batch.id(), template, id); return (RuntimeException) null; }
                    catch (RuntimeException rejected) { return rejected; }
                    finally { SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId"); }
                });
                awaitSendCheckLockWait(blockerPid, sendCheck);
                AgentPersonalEnablementIntegrationTest.TIME.set(now.plusSeconds(61));
                transitions.recoverExpired(requests.find(project, id).orElseThrow(), now.plusSeconds(1));
                assertThat(requests.find(project, id).orElseThrow().view().state()).isEqualTo("FAILED");
                blocker.commit();
                assertThat(sendCheck.get(10, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(AgentEnablementInvalidStateException.class);
            } finally { blocker.rollback(); }
        } finally {
            executor.shutdownNow(); assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertPendingReservation(batch.id(), id);
        assertSendRejectionCount(1);
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

    @Test void changedProvenanceDeniesNewAttemptsButCannotEraseAnIncurredCharge() {
        var batch = batch(2, 8400); UUID first = request(batch.id(), true), second = request(batch.id(), true);
        accounting.reserve(project, batch.id(), template, first);
        INVENTORY_SHA.set("9".repeat(64));
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), template, second)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(accounting.settle(project, first, LiveCostPolicy.VERSION, new LiveTokenUsage(1000, 100, 500, 1100, 0)).actualMicrousd()).isEqualTo(273);
        assertThat(ledger.batch(project, batch.id()).orElseThrow().inventoryProof().fileSha256()).isEqualTo("e".repeat(64));
        assertThatThrownBy(() -> jdbc.sql("UPDATE agent.live_batches SET inventory_proof='{}' WHERE id=:id").param("id", batch.id()).update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE project_id=:p").param("p", project).query(Integer.class).single()).isOne();
    }

    @Test void legacyBatchIsReadableButCannotAdmitAndApprovalCannotOutliveInventory() {
        var approved = batch(1, 4200); var original = ledger.batch(project, approved.id()).orElseThrow();
        var legacy = new LiveBatchView(UUID.randomUUID(), project, acknowledgement, OWNER, now, approved.manifestSha256(), now.plusSeconds(3600), 1, 4200, false);
        ledger.insert(new LiveAccountingStore.Batch(legacy, DEPLOYMENT, original.inventoryId(), original.manifest(), null));
        UUID request = request(legacy.id(), true);
        assertThat(accounting.get(project, legacy.id())).isEqualTo(legacy);
        assertThatThrownBy(() -> accounting.reserve(project, legacy.id(), template, request)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThatThrownBy(() -> accounting.approve(project, "http://localhost:8083", false,
            new LiveBatchInput(acknowledgement, original.inventoryId(), List.of(template), approved.manifestSha256(), now.plusSeconds(3601), 1, 4200, true)))
            .isInstanceOf(AgentInvalidException.class);
    }

    // 真实并发结算最多写一份结果和一次费用。
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

    // 未知或非法用量不生成零费用，保留预留且禁止退款重试。
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
        UUID freshRequest = request(fresh.id(), true);
        AgentPersonalEnablementIntegrationTest.TIME.set(now.plusSeconds(3600));
        assertThatThrownBy(() -> accounting.reserve(project, fresh.id(), template, freshRequest)).isInstanceOf(AgentEnablementInvalidStateException.class);
        AgentPersonalEnablementIntegrationTest.TIME.set(now);
        AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.set(new io.github.aiarchguard.platform.agentcredential.CredentialStatus(true, UUID.randomUUID(), now));
        assertThatThrownBy(() -> accounting.reserve(project, fresh.id(), template, freshRequest)).isInstanceOf(AgentEnablementInvalidStateException.class);
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

    // 历史不可变由数据库约束共同保护，不能只依赖服务约定。
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

    // 结算不恢复批次次数或累计费用上限，避免反复使用同一批准。
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

    // 不同 Project 仍竞争同一部署额度，不能借项目隔离突破全局日上限。
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

    // 成功审计与账本动作原子提交，审计失败不留半次预留或结算。
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
    void assertPendingReservation(UUID batchId, UUID requestId) {
        var attempt = ledger.attempt(project, requestId).orElseThrow();
        assertThat(attempt.batchId()).isEqualTo(batchId);
        assertThat(attempt.reservation()).isEqualTo(4200);
        assertThat(attempt.outcome()).isNull();
        assertThat(attempt.actual()).isNull();
        assertThat(amount("PROJECT", project, "reserved_microusd")).isEqualTo(4200);
        assertThat(amount("PROJECT", project, "spent_microusd")).isZero();
        assertThat(amount("DEPLOYMENT", DEPLOYMENT, "reserved_microusd")).isEqualTo(4200);
        assertThat(jdbc.sql("SELECT attempts FROM agent.live_batch_usage WHERE batch_id=:b").param("b", batchId).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT reserved_microusd FROM agent.live_batch_usage WHERE batch_id=:b").param("b", batchId).query(Long.class).single()).isEqualTo(4200);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE project_id=:p").param("p", project).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_outcomes WHERE request_id=:id").param("id", requestId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.reserved'")
            .param("p", project).query(Integer.class).single()).isOne();
    }
    void assertSendRejectionCount(int count) {
        assertThat(jdbc.sql("""
            SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.write_rejected'
                AND metadata->>'operation'='agent.live.send_check'
            """).param("p", project).query(Integer.class).single()).isEqualTo(count);
    }
    void awaitSendCheckLockWait(int blockerPid, java.util.concurrent.Future<?> sendCheck) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            assertThat(sendCheck.isDone()).as("Send check must reach the held batch-usage row").isFalse();
            int waiting = jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity a WHERE a.datname=current_database()
                    AND a.wait_event_type='Lock' AND :blocker=ANY(pg_blocking_pids(a.pid))
                """).param("blocker", blockerPid).query(Integer.class).single();
            if (waiting > 0) return;
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.currentThread().isInterrupted()) throw new AssertionError("Lock-state observation was interrupted");
        }
        throw new AssertionError("Send check did not enter the expected PostgreSQL lock wait within 10 seconds");
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
        @Bean @Primary SyntheticBatchInventory approvedSyntheticInventory() {
            return (project, inventory, hash) -> INVENTORY.contains(project + ":" + hash)
                ? java.util.Optional.of(new SyntheticBatchInventory.Proof("0.1.0", INVENTORY_SHA.get(), "synthetic-v1", "f".repeat(64),
                    "synthetic-review", INVENTORY_REVIEWED_AT.get(), INVENTORY_REVIEWED_AT.get().plusSeconds(3600)))
                : java.util.Optional.empty();
        }
    }
}
