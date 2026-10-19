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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
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
class AgentContextAccountingIntegrationTest extends AgentLiveDatabaseIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentEnablementOperations enablements;
    @Autowired AgentEnablementStore storedEnablements;
    @Autowired AgentStore requests;
    @Autowired LiveRequestStore links;
    @Autowired LiveAccountingOperations accounting;
    @Autowired LiveRequestOperations creation;
    @Autowired LiveAccountingStore ledger;
    @Autowired io.github.aiarchguard.platform.project.ProjectAuthorization projects;
    @Autowired io.github.aiarchguard.platform.identity.CurrentActorProvider actors;
    @Autowired io.github.aiarchguard.platform.common.TraceIdProvider traces;
    @Autowired io.github.aiarchguard.platform.audit.AuditRecorder audit;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired LiveDatabaseWork database;
    @Autowired java.time.Clock clock;
    private AgentLiveAccountingIntegrationTest legacy;
    private UUID project, approval, explanation, summary;
    private Instant now;

    @BeforeEach void prepare() throws Exception {
        legacy = new AgentLiveAccountingIntegrationTest();
        legacy.mvc = mvc; legacy.mapper = mapper; legacy.jdbc = jdbc; legacy.enablements = enablements;
        legacy.requests = requests; legacy.links = links; legacy.accounting = accounting; legacy.ledger = ledger;
        legacy.prepare(); project = legacy.project; now = legacy.now; explanation = legacy.template;
        var old = new AgentPersonalEnablementIntegrationTest();
        ObjectNode input = mapper.valueToTree(old.input());
        input.put("schemaVersion", "0.4.0").put("priceCatalogVersion", LiveCostPolicy.CONTEXT_VERSION)
            .put("mappingSnapshot", "DeepSeek-V4.1-Flash-1017")
            .put("expiresAt", now.plusSeconds(86400).toString()).put("priceCatalogExpiresAt", now.plusSeconds(86400).toString());
        for (var source : input.path("sources")) ((ObjectNode) source).put("checkedAt", now.toString());
        approval = enablements.approve(project, mapper.treeToValue(input, PersonalEnablementInput.class)).id();
        enablements.update(project, enablements.settings(project).revision(), new AgentSettingsUpdate(true, approval));
        summary = insert(null, null, "PR_SUMMARY", false);
    }

    @AfterEach void clear() { Thread.interrupted(); SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId"); }

    @Test void independentPolicyReserves316373AndSecondWaitsForValidatedSuccessNotJustSettlement() {
        var batch = batch(700000); UUID first = insert(batch.id(), explanation, "FINDING_EXPLANATION", true);
        UUID second = insert(batch.id(), summary, "PR_SUMMARY", true);
        assertThat(accounting.reserve(project, batch.id(), explanation, first).reservedMicrousd()).isEqualTo(316373);
        assertThat(ledger.batch(project, batch.id()).orElseThrow().costPolicyVersion()).isEqualTo(LiveCostPolicy.CONTEXT_VERSION);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), summary, second)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(accounting.settle(project, first, LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(1000, 100, 500, 1100, 0)).actualMicrousd()).isEqualTo(273);
        assertThat(requests.find(project, first).orElseThrow().view().state()).isEqualTo("RUNNING");
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), summary, second)).isInstanceOf(AgentEnablementInvalidStateException.class);
        succeeded(first);
        assertThat(accounting.reserve(project, batch.id(), summary, second).newAttempt()).isTrue();
        assertThat(accounting.checkBeforeSend(project, batch.id(), summary, second).credentialVersion()).isEqualTo(AgentPersonalEnablementIntegrationTest.CREDENTIAL);
        assertThat(accounting.reserve(project, batch.id(), summary, second).newAttempt()).isFalse();
        assertThat(ledger.lockBatch(batch.id()).attempts()).isEqualTo(2);
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isEqualTo(316373);
        assertThat(legacy.amount("PROJECT", project, "spent_microusd")).isEqualTo(273);
    }

    @Test void oldContextMappingCannotApproveANewBatchOrCreateAndReserveANewLiveAttempt() throws Exception {
        var historical = historicalContextBatch();
        assertThat(enablements.settings(project).enablementId()).isEqualTo(approval);
        assertThat(historical.enablementId()).isEqualTo(approval);
        assertThatThrownBy(() -> batch(700000)).isInstanceOf(AgentEnablementInvalidStateException.class).hasNoCause();
        assertThatThrownBy(() -> creation.create(project, historical.id(), explanation,
            "old-context-create", "http://localhost:8083", false)).isInstanceOf(AgentEnablementInvalidStateException.class).hasNoCause();
        assertThatThrownBy(() -> creation.create(project, historical.id(), explanation,
            "old-context-bounded-create", "http://localhost:8083", false, () -> java.util.concurrent.TimeUnit.SECONDS.toNanos(5)))
            .isInstanceOf(AgentEnablementInvalidStateException.class).hasNoCause();
        assertThat(requests.findByKey(project, AgentLiveAccountingIntegrationTest.OWNER, "old-context-create")).isEmpty();
        assertThat(requests.findByKey(project, AgentLiveAccountingIntegrationTest.OWNER, "old-context-bounded-create")).isEmpty();
        UUID first = insert(historical.id(), explanation, "FINDING_EXPLANATION", true);
        assertThatThrownBy(() -> accounting.reserve(project, historical.id(), explanation, first))
            .isInstanceOf(AgentEnablementInvalidStateException.class).hasNoCause();
        assertThatThrownBy(() -> accounting.reserve(project, historical.id(), explanation, first,
            () -> java.util.concurrent.TimeUnit.SECONDS.toNanos(5)))
            .isInstanceOf(AgentEnablementInvalidStateException.class).hasNoCause();
        assertThat(ledger.attempt(project, first)).isEmpty();
        assertThat(ledger.lockBatch(historical.id()).attempts()).isZero();
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isZero();
    }

    @Test void oldContextAttemptCannotPassEitherSendRecheckButItsAccruedFeeSettlesAtItsOriginalPrice() throws Exception {
        var historical = historicalContextBatch();
        UUID first = insert(historical.id(), explanation, "FINDING_EXPLANATION", true);
        var day = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        ledger.lockBudget("DEPLOYMENT", AgentLiveAccountingIntegrationTest.DEPLOYMENT, day);
        ledger.lockBudget("PROJECT", project, day);
        ledger.reserveBudget("DEPLOYMENT", AgentLiveAccountingIntegrationTest.DEPLOYMENT, day, 316373);
        ledger.reserveBudget("PROJECT", project, day, 316373);
        ledger.reserveBatch(historical.id(), 316373);
        var attempt = new LiveAccountingStore.Attempt(first, project, historical.id(), explanation, UUID.randomUUID(),
            AgentLiveAccountingIntegrationTest.DEPLOYMENT, day, "deepseek-flash-context-peak-usd-2026-10-15", 316373, null, null);
        ledger.insertAttempt(attempt, approval, AgentPersonalEnablementIntegrationTest.CREDENTIAL,
            enablements.settings(project).revision(), now);
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, historical.id(), explanation, first))
            .isInstanceOf(AgentEnablementInvalidStateException.class).hasNoCause();
        assertThatThrownBy(() -> accounting.checkBeforeSend(project, historical.id(), explanation, first,
            () -> java.util.concurrent.TimeUnit.SECONDS.toNanos(5)))
            .isInstanceOf(AgentEnablementInvalidStateException.class).hasNoCause();
        assertThat(ledger.attempt(project, first).orElseThrow()).isEqualTo(attempt);
        enablements.revoke(project, approval);
        var outcome = accounting.settle(project, first, "deepseek-flash-context-peak-usd-2026-10-15",
            new LiveTokenUsage(1000, 100, 500, 1100, 0));
        assertThat(outcome.state()).isEqualTo("SETTLED");
        assertThat(outcome.actualMicrousd()).isEqualTo(273);
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isZero();
        assertThat(legacy.amount("PROJECT", project, "spent_microusd")).isEqualTo(273);
        assertThat(ledger.attempt(project, first).orElseThrow().priceVersion()).isEqualTo("deepseek-flash-context-peak-usd-2026-10-15");
        assertThat(storedEnablements.find(project, approval).orElseThrow().acknowledgement().schemaVersion()).isEqualTo("0.3.0");
    }

    private LiveBatchView historicalContextBatch() throws Exception {
        var current = ledger.batch(project, batch(700000).id()).orElseThrow();
        ObjectNode input = mapper.valueToTree(storedEnablements.find(project, approval).orElseThrow().acknowledgement());
        input.put("schemaVersion", "0.3.0").put("mappingSnapshot", "deepseek-v4.1-flash-2026-10-15");
        approval = enablements.approve(project, mapper.treeToValue(input, PersonalEnablementInput.class)).id();
        enablements.update(project, enablements.settings(project).revision(), new AgentSettingsUpdate(true, approval));
        var before = current.view();
        var historical = new LiveBatchView(UUID.randomUUID(), project, approval, before.approvedBy(), before.approvedAt(),
            before.manifestSha256(), before.expiresAt(), before.maxRequests(), before.maxCostMicrousd(), false);
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status ->
            ledger.insert(new LiveAccountingStore.Batch(historical, current.deploymentId(), current.inventoryId(),
                current.manifest(), current.inventoryProof(), current.costPolicyVersion())));
        return historical;
    }

    @Test void newVersionRequestCanBeClaimedExactlyOnceWithoutTreatingItAsLegacy() {
        var batch = batch(700000);
        UUID first = insert(batch.id(), explanation, "FINDING_EXPLANATION", true, "QUEUED");
        var link = links.find(project, first).orElseThrow();
        assertThat(links.claim(link, AgentLiveAccountingIntegrationTest.OWNER, now)).isTrue();
        assertThat(links.claim(link, AgentLiveAccountingIntegrationTest.OWNER, now)).isFalse();
        assertThat(requests.find(project, first).orElseThrow().view().bindings().priceCatalogVersion()).isEqualTo(LiveCostPolicy.CONTEXT_VERSION);
        assertThat(ledger.attempt(project, first)).isEmpty();
    }

    @Test void trustedPreflightFailureBeforeAnAttemptStillStopsReplacementRequests() {
        var batch = batch(700000); UUID first = insert(batch.id(), explanation, "FINDING_EXPLANATION", true);
        assertThat(ledger.attempt(project, first)).isEmpty();
        accounting.stopBatch(project, batch.id(), first, "OUTPUT_INVALID");
        assertThat(stopCode(batch.id())).isEqualTo("OUTPUT_INVALID");
        assertReplacementLinkRejected(batch.id());
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), explanation, first))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        UUID remaining = insert(batch.id(), summary, "PR_SUMMARY", true);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), summary, remaining))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(ledger.attempt(project, remaining)).isEmpty();
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isZero();
    }

    @Test void committedClaimOwnerPersistsPreflightStopAndBoundedFailureWithoutAnyProviderAttempt() {
        var batch = batch(700000); UUID first = insert(batch.id(), explanation, "FINDING_EXPLANATION", true, "QUEUED");
        org.slf4j.MDC.put("traceId", "c".repeat(32));
        var scope = new io.github.aiarchguard.platform.identity.AuthenticatedTaskScope() {
            public Runnable capture(UUID expected, Runnable action) { return action; }
            public void requireActive() { /* Test-only identity capability; never a production bean. */ }
        };
        var transition = new LiveDispatchTransitions(requests, links, projects, actors, scope, traces, audit, clock, manager, accounting, database);
        var claim = transition.start(project, first); assertThat(claim).isNotNull();
        transition.fail(claim, "OUTPUT_INVALID");
        assertThat(stopCode(batch.id())).isEqualTo("OUTPUT_INVALID");
        assertThat(requests.find(project, first).orElseThrow().view().state()).isEqualTo("FAILED");
        assertThat(ledger.attempt(project, first)).isEmpty();
        assertReplacementLinkRejected(batch.id());
        UUID remaining = insert(batch.id(), summary, "PR_SUMMARY", true);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), summary, remaining))
            .isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(ledger.attempt(project, remaining)).isEmpty();
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isZero();
    }

    @Test void newBatchClaimRejectsCrossPolicyProfilesAndEvenACorrectLegacyPair() {
        for (String[] pair : new String[][]{{LiveAccountingOperations.MODEL_PROFILE, LiveCostPolicy.CONTEXT_VERSION},
                {LiveCostPolicy.CONTEXT_MODEL_PROFILE, LiveCostPolicy.VERSION}, {LiveAccountingOperations.MODEL_PROFILE, LiveCostPolicy.VERSION}}) {
            var batch = batch(700000);
            UUID id = insert(batch.id(), explanation, "FINDING_EXPLANATION", true, "QUEUED", pair[0], pair[1]);
            assertThat(links.claim(links.find(project, id).orElseThrow(), AgentLiveAccountingIntegrationTest.OWNER, now)).isFalse();
            assertThat(requests.find(project, id).orElseThrow().view().state()).isEqualTo("QUEUED");
        }
    }

    @Test void overObservationThresholdSettlesKnownCostAndAtomicallyStopsEvenIfResultIsNotYetFailed() {
        var batch = batch(700000); UUID first = insert(batch.id(), explanation, "FINDING_EXPLANATION", true);
        UUID second = insert(batch.id(), summary, "PR_SUMMARY", true);
        accounting.reserve(project, batch.id(), explanation, first);
        var outcome = accounting.settle(project, first, LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(8001, 100, 0, 8101, 0));
        assertThat(outcome.state()).isEqualTo("SETTLED"); assertThat(outcome.actualMicrousd()).isEqualTo(2521);
        assertThat(stopCode(batch.id())).isEqualTo("INPUT_TOKEN_LIMIT");
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isZero();
        assertThat(legacy.amount("PROJECT", project, "spent_microusd")).isEqualTo(2521);
        // Even an erroneous later success must not undo the independently committed stop.
        succeeded(first);
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), summary, second)).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(ledger.attempt(project, second)).isEmpty();
        assertThat(accounting.settle(project, first, LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(1, 0, 0, 1, 0))).isEqualTo(outcome);
        assertThat(stopCode(batch.id())).isEqualTo("INPUT_TOKEN_LIMIT");
    }

    @Test void unknownUsageKeepsTheFullReservationAndStopsNoReplacementAttempt() {
        var batch = batch(700000); UUID first = insert(batch.id(), explanation, "FINDING_EXPLANATION", true);
        UUID second = insert(batch.id(), summary, "PR_SUMMARY", true);
        accounting.reserve(project, batch.id(), explanation, first);
        assertThat(accounting.unknown(project, first).state()).isEqualTo("UNKNOWN");
        assertThat(stopCode(batch.id())).isEqualTo("OUTPUT_INVALID");
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isEqualTo(316373);
        assertThat(accounting.settle(project, first, LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(100, 0, 0, 100, 0)).state()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), summary, second)).isInstanceOf(AgentEnablementInvalidStateException.class);
    }

    @Test void outputFailureStopIsAppendOnlyAndCannotRefundKnownCostOrBeUndoneByReplay() {
        var batch = batch(700000); UUID first = insert(batch.id(), explanation, "FINDING_EXPLANATION", true);
        accounting.reserve(project, batch.id(), explanation, first);
        accounting.settle(project, first, LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(1000, 100, 500, 1100, 0));
        accounting.stopBatch(project, batch.id(), first, "CITATION_INVALID");
        accounting.stopBatch(project, batch.id(), first, "MODEL_TIMEOUT");
        assertThat(stopCode(batch.id())).isEqualTo("CITATION_INVALID");
        assertThat(legacy.amount("PROJECT", project, "spent_microusd")).isEqualTo(273);
        assertThat(accounting.reserve(project, batch.id(), explanation, first).newAttempt()).isFalse();
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM agent.live_batch_stops WHERE batch_id=:id").param("id", batch.id()).update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void batchRequiresTwoPurposesAndExplicitNewBudgetAndSummaryCannotGoFirst() {
        for (long cap : new long[]{4200, 632745, 700001, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> batch(cap)).isInstanceOf(AgentInvalidException.class);
        }
        var accepted = batch(632746); UUID second = insert(accepted.id(), summary, "PR_SUMMARY", true);
        assertThatThrownBy(() -> accounting.reserve(project, accepted.id(), summary, second)).isInstanceOf(AgentEnablementInvalidStateException.class);
        String digest = accounting.preview(project, List.of(explanation));
        AgentLiveAccountingIntegrationTest.INVENTORY.add(project + ":" + digest);
        assertThatThrownBy(() -> accounting.approve(project, "http://localhost:8083", false,
            new LiveBatchInput(approval, UUID.randomUUID(), List.of(explanation), digest, now.plusSeconds(3600), 2, 700000, true)))
            .isInstanceOf(AgentInvalidException.class);
    }

    @Test void newReservationUsesUnchangedProjectDailyLimitAndRejectsInsufficientHeadroom() {
        var batch = batch(700000); UUID first = insert(batch.id(), explanation, "FINDING_EXPLANATION", true);
        ledger.lockBudget("PROJECT", project, now.atZone(java.time.ZoneOffset.UTC).toLocalDate());
        jdbc.sql("UPDATE agent.live_budget_usage SET spent_microusd=:amount WHERE scope='PROJECT' AND scope_id=:id")
            .param("amount", 5_000_000 - 316373 + 1).param("id", project).update();
        assertThatThrownBy(() -> accounting.reserve(project, batch.id(), explanation, first)).isInstanceOf(AgentQuotaExceededException.class);
        assertThat(ledger.attempt(project, first)).isEmpty();
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isZero();
        assertThat(ledger.lockBatch(batch.id()).attempts()).isZero();
    }

    @Test void legacyAttemptStillSettlesItsOwn4200PolicyAfterNewConfirmationIsSelected() {
        UUID id = historicalAttempt(LiveCostPolicy.VERSION);
        var outcome = accounting.settle(project, id, LiveCostPolicy.VERSION, new LiveTokenUsage(1000, 100, 500, 1100, 0));
        assertThat(outcome.actualMicrousd()).isEqualTo(273);
        assertThat(ledger.attempt(project, id).orElseThrow().reservation()).isEqualTo(4200);
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isZero();
        assertThat(enablements.settings(project).enablementId()).isEqualTo(approval);
    }

    @Test void unrecognizedHistoricPriceNeverUsesNewOrLegacyRatesAndRetainsOriginalReservation() {
        UUID id = historicalAttempt("historical-unknown-price");
        assertThat(accounting.settle(project, id, "historical-unknown-price", new LiveTokenUsage(1, 0, 0, 1, 0)).state()).isEqualTo("UNKNOWN");
        assertThat(legacy.amount("PROJECT", project, "reserved_microusd")).isEqualTo(4200);
        assertThat(ledger.attempt(project, id).orElseThrow().actual()).isNull();
    }

    private UUID historicalAttempt(String price) {
        var oldBatch = legacy.batch(1, 4200); UUID id = legacy.request(oldBatch.id(), true);
        var day = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        ledger.lockBudget("DEPLOYMENT", AgentLiveAccountingIntegrationTest.DEPLOYMENT, day); ledger.lockBudget("PROJECT", project, day);
        ledger.reserveBudget("DEPLOYMENT", AgentLiveAccountingIntegrationTest.DEPLOYMENT, day, 4200);
        ledger.reserveBudget("PROJECT", project, day, 4200); ledger.reserveBatch(oldBatch.id(), 4200);
        ledger.insertAttempt(new LiveAccountingStore.Attempt(id, project, oldBatch.id(), explanation, UUID.randomUUID(),
            AgentLiveAccountingIntegrationTest.DEPLOYMENT, day, price, 4200, null, null), legacy.acknowledgement,
            AgentPersonalEnablementIntegrationTest.CREDENTIAL, enablements.settings(project).revision(), now);
        return id;
    }

    private LiveBatchView batch(long cap) {
        List<UUID> templates = List.of(explanation, summary); String digest = accounting.preview(project, templates);
        AgentLiveAccountingIntegrationTest.INVENTORY.add(project + ":" + digest);
        return accounting.approve(project, "http://localhost:8083", false,
            new LiveBatchInput(approval, UUID.randomUUID(), templates, digest, now.plusSeconds(3600), 2, cap, true));
    }

    private void assertReplacementLinkRejected(UUID batch) {
        long before = jdbc.sql("SELECT count(*) FROM agent.requests WHERE project_id=:id").param("id", project).query(Long.class).single();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> insert(batch, explanation, "FINDING_EXPLANATION", true)))
            .isInstanceOf(AgentConflictException.class).hasNoCause();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.requests WHERE project_id=:id").param("id", project).query(Long.class).single())
            .isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_request_links WHERE project_id=:project AND batch_id=:batch AND template_id=:template")
            .param("project", project).param("batch", batch).param("template", explanation).query(Long.class).single()).isOne();
    }

    private UUID insert(UUID batch, UUID template, String purpose, boolean live) {
        return insert(batch, template, purpose, live, live ? "RUNNING" : "FAILED");
    }
    private UUID insert(UUID batch, UUID template, String purpose, boolean live, String state) {
        return insert(batch, template, purpose, live, state,
            live ? LiveCostPolicy.CONTEXT_MODEL_PROFILE : "disabled-or-test-fake-0.1.0",
            live ? LiveCostPolicy.CONTEXT_VERSION : "synthetic-0.1.0");
    }
    private UUID insert(UUID batch, UUID template, String purpose, boolean live, String state, String profile, String price) {
        var source = requests.find(project, live ? template : explanation).orElseThrow();
        var old = source.view().bindings(); var input = source.input();
        String prompt = live ? (purpose.equals("PR_SUMMARY") ? LiveAccountingOperations.SUMMARY_PROMPT : LiveAccountingOperations.EXPLANATION_PROMPT)
            : "pr-summary-0.1.0";
        var modelInput = new AgentModelPort.ModelInput(purpose, input.schemaVersion(), prompt, input.findingRef(), input.ruleId(),
            input.ruleVersion(), input.severity(), input.message(), input.subject(), input.evidence(), input.documents(), input.findings());
        var bindings = new AgentRequestView.VersionBindings(old.scanJobId(), old.reportSha256(), old.prHeadRevisionId(), old.findingIds(),
            old.documentVersions(), prompt, profile,
            old.modelProtocolVersion(), live ? "deepseek-flash" : "deterministic-fake-or-disabled", old.outputSchemaVersion(),
            price, live ? accounting.requestDigest(project, batch, template) : "d".repeat(64));
        UUID id = UUID.randomUUID();
        var view = new AgentRequestView(id, project, AgentLiveAccountingIntegrationTest.OWNER, "c".repeat(32), purpose,
            state, bindings, null, live ? null : new AgentRequestView.AgentFailure("MODEL_DISABLED", "Synthetic"), null, now, now);
        assertThat(requests.insert(new AgentSnapshot(view, modelInput, source.candidates()), id.toString())).isTrue();
        if (live) links.insert(new LiveRequestStore.Link(id, project, batch, template, approval,
            AgentPersonalEnablementIntegrationTest.CREDENTIAL, enablements.settings(project).revision(), now));
        return id;
    }

    private void succeeded(UUID request) {
        assertThat(requests.finish(request, new AgentRequestView.AgentResult("Synthetic", List.of(), List.of(), List.of(), List.of(), "INSUFFICIENT"),
            null, null, now)).isTrue();
    }

    private String stopCode(UUID batch) {
        return jdbc.sql("SELECT code FROM agent.live_batch_stops WHERE batch_id=:id").param("id", batch).query(String.class).single();
    }
}
