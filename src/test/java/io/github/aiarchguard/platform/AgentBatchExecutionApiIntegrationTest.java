package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.AgentEnablementOperations;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.AgentSettingsUpdate;
import io.github.aiarchguard.platform.agent.LiveBatchInput;
import io.github.aiarchguard.platform.agent.LiveBatchView;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.LiveTokenUsage;
import io.github.aiarchguard.platform.agent.PersonalEnablementInput;
import io.github.aiarchguard.platform.agent.internal.AgentSnapshot;
import io.github.aiarchguard.platform.agent.internal.AgentStore;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingOperations;
import io.github.aiarchguard.platform.agent.internal.LiveAccountingStore;
import io.github.aiarchguard.platform.agent.internal.LiveManifest;
import io.github.aiarchguard.platform.agent.internal.LiveRequestStore;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 通过只读 HTTP 与真实数据库验证执行快照只呈现持久化事实。请求分配、attempt、结算和业务结果分别建模；GET 不读 Key、不修复历史、不产生发送许可。
 *
 * Only persisted facts are observable: a GET must not obtain credentials or repair history.
 */
@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import({AgentPersonalEnablementIntegrationTest.Config.class, AgentLiveAccountingIntegrationTest.Config.class})
class AgentBatchExecutionApiIntegrationTest extends AgentLiveDatabaseIntegrationTestSupport {
    private static final String OWNER = AgentPersonalEnablementIntegrationTest.OWNER;
    private static final LiveTokenUsage KNOWN_USAGE = new LiveTokenUsage(1000, 100, 500, 1100, 0);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentEnablementOperations enablements;
    @Autowired AgentStore requests;
    @Autowired LiveRequestStore links;
    @Autowired LiveAccountingOperations accounting;
    @Autowired LiveAccountingStore ledger;
    @Autowired PlatformTransactionManager manager;
    private AgentLiveAccountingIntegrationTest legacy;
    private UUID project, approval, explanation, summary;
    private Instant now;

    @BeforeEach void prepare() throws Exception {
        AgentPersonalEnablementIntegrationTest.REQUIRE_BOUNDED_METADATA.set(false);
        legacy = new AgentLiveAccountingIntegrationTest();
        legacy.mvc = mvc; legacy.mapper = mapper; legacy.jdbc = jdbc; legacy.enablements = enablements;
        legacy.requests = requests; legacy.links = links; legacy.accounting = accounting; legacy.ledger = ledger;
        legacy.prepare(); project = legacy.project; now = legacy.now; explanation = legacy.template;
        ObjectNode input = mapper.valueToTree(new AgentPersonalEnablementIntegrationTest().input());
        input.put("schemaVersion", "0.4.0").put("priceCatalogVersion", LiveCostPolicy.CONTEXT_VERSION)
            .put("mappingSnapshot", "DeepSeek-V4.1-Flash-1017")
            .put("expiresAt", now.plusSeconds(86400).toString()).put("priceCatalogExpiresAt", now.plusSeconds(86400).toString());
        for (var source : input.path("sources")) ((ObjectNode) source).put("checkedAt", now.toString());
        approval = enablements.approve(project, mapper.treeToValue(input, PersonalEnablementInput.class)).id();
        enablements.update(project, enablements.settings(project).revision(), new AgentSettingsUpdate(true, approval));
        summary = insert(null, explanation, "PR_SUMMARY", "FAILED", false);
        SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId");
    }

    @AfterEach void clear() {
        Thread.interrupted(); SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId");
        AgentPersonalEnablementIntegrationTest.REQUIRE_BOUNDED_METADATA.set(false);
    }

    // 无执行事实时仍呈现固定模板，不虚构请求、attempt 或可发送状态。
    @Test void emptySnapshotHasExactlyTwoOrderedTemplatesNoInventedRequestsOrSendingPermission() throws Exception {
        var batch = batch(); var before = databaseSnapshot(batch.id());
        JsonNode view = view(batch.id());
        exactFields(view, "schemaVersion", "observedAt", "batch", "costPolicyVersion", "accounting", "stop", "templates");
        assertThat(view.path("schemaVersion").asText()).isEqualTo("0.1.0");
        assertThat(Instant.parse(view.path("observedAt").asText())).isNotNull();
        assertThat(view.path("costPolicyVersion").asText()).isEqualTo(LiveCostPolicy.CONTEXT_VERSION);
        assertThat(view.path("stop").isNull()).isTrue();
        exactFields(view.path("batch"), "id", "projectId", "enablementId", "approvedBy", "approvedAt", "manifestSha256",
            "expiresAt", "maxRequests", "maxCostMicrousd", "revoked");
        assertThat(view.path("batch").path("id").asText()).isEqualTo(batch.id().toString());
        assertThat(view.path("batch").path("maxCostMicrousd").asLong()).isEqualTo(700000);
        accounting(view, 0, 0, 0);
        assertThat(view.path("templates").size()).isEqualTo(2);
        for (int index = 0; index < 2; index++) {
            JsonNode template = view.path("templates").get(index);
            exactFields(template, "templateRequestId", "purpose", "request", "attempt");
            assertThat(template.path("templateRequestId").asText()).isEqualTo((index == 0 ? explanation : summary).toString());
            assertThat(template.path("purpose").asText()).isEqualTo(index == 0 ? "FINDING_EXPLANATION" : "PR_SUMMARY");
            assertThat(template.path("request").isNull()).isTrue();
            assertThat(template.path("attempt").isNull()).isTrue();
        }
        assertThat(view.toString()).doesNotContain("canCall", "retryAllowed", "remainingRequests", "sentCount", "apiKey",
            "credentialVersion", "inventoryId", "inventoryProof", "model_input", "Synthetic evidence", "candidates");
        assertThat(databaseSnapshot(batch.id())).isEqualTo(before);
    }

    // 刷新可找回同一个永久请求 ID，领取不代表已经调用模型。
    @Test void allocatedQueuedAndClaimedWithoutAttemptRecoverTheSamePermanentRequestId() throws Exception {
        var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "QUEUED");
        var before = databaseSnapshot(batch.id()); JsonNode queued = view(batch.id());
        assertRequest(queued, id, "QUEUED", null);
        assertThat(first(queued).path("attempt").isNull()).isTrue(); accounting(queued, 0, 0, 0);
        assertThat(databaseSnapshot(batch.id())).isEqualTo(before);
        assertThat(links.claim(links.find(project, id).orElseThrow(), AgentLiveAccountingIntegrationTest.OWNER, now)).isTrue();
        JsonNode running = view(batch.id()); assertRequest(running, id, "RUNNING", null);
        assertThat(first(running).path("attempt").isNull()).isTrue(); accounting(running, 0, 0, 0);
        assertThat(view(batch.id()).path("templates").get(0).path("request").path("id").asText()).isEqualTo(id.toString());
    }

    // 预留或结算存在不能被展示成业务成功。
    @Test void pendingReservationAndSettledChargeAreNotInventedAsSuccessfulResult() throws Exception {
        var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        var admission = accounting.reserve(project, batch.id(), explanation, id);
        JsonNode pending = view(batch.id()); assertRequest(pending, id, "RUNNING", null); accounting(pending, 1, 316373, 0);
        JsonNode attempt = first(pending).path("attempt");
        exactFields(attempt, "attemptId", "utcDay", "priceCatalogVersion", "reservedMicrousd", "createdAt", "outcome");
        assertThat(attempt.path("attemptId").asText()).isEqualTo(admission.attemptId().toString());
        assertThat(attempt.path("utcDay").asText()).isEqualTo(now.atZone(ZoneOffset.UTC).toLocalDate().toString());
        assertThat(attempt.path("priceCatalogVersion").asText()).isEqualTo(LiveCostPolicy.CONTEXT_VERSION);
        assertThat(attempt.path("reservedMicrousd").asLong()).isEqualTo(316373);
        assertThat(attempt.path("outcome").isNull()).isTrue();
        AgentLiveAccountingIntegrationTest.identity(AgentLiveAccountingIntegrationTest.OWNER);
        accounting.settle(project, id, LiveCostPolicy.CONTEXT_VERSION, KNOWN_USAGE);
        JsonNode settled = view(batch.id()); assertRequest(settled, id, "RUNNING", null); accounting(settled, 1, 0, 273);
        JsonNode outcome = first(settled).path("attempt").path("outcome");
        exactFields(outcome, "state", "actualMicrousd", "usage", "recordedAt");
        assertThat(outcome.path("state").asText()).isEqualTo("SETTLED");
        assertThat(outcome.path("actualMicrousd").asLong()).isEqualTo(273);
        exactFields(outcome.path("usage"), "inputTokens", "outputTokens", "cachedInputTokens", "totalTokens", "reasoningTokens");
        assertThat(outcome.path("usage")).isEqualTo(mapper.valueToTree(KNOWN_USAGE));
        assertThat(settled.path("templates").get(1).path("request").isNull()).isTrue();
        assertThat(settled.path("stop").isNull()).isTrue();
    }

    @Test void knownChargeSurvivesFailedResultAndOnlyFixedFailureCodeIsExposed() throws Exception {
        var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        accounting.reserve(project, batch.id(), explanation, id);
        accounting.settle(project, id, LiveCostPolicy.CONTEXT_VERSION, KNOWN_USAGE);
        assertThat(requests.failRunning(id, new AgentRequestView.AgentFailure("CITATION_INVALID", "private-synthetic-diagnostic"), now)).isTrue();
        accounting.stopBatch(project, batch.id(), id, "CITATION_INVALID");
        var before = databaseSnapshot(batch.id()); JsonNode view = view(batch.id());
        assertRequest(view, id, "FAILED", "CITATION_INVALID"); accounting(view, 1, 0, 273);
        assertThat(first(view).path("attempt").path("outcome").path("state").asText()).isEqualTo("SETTLED");
        exactFields(view.path("stop"), "requestId", "code", "stoppedAt");
        assertThat(view.path("stop").path("requestId").asText()).isEqualTo(id.toString());
        assertThat(view.path("stop").path("code").asText()).isEqualTo("CITATION_INVALID");
        assertThat(view.toString()).doesNotContain("private-synthetic-diagnostic", "result", "failureMessage");
        assertThat(databaseSnapshot(batch.id())).isEqualTo(before);
    }

    // UNKNOWN 保留全额预留与停批，不猜测 token 或退款。
    @Test void unknownOutcomeRetainsFullReservationAndStopWithoutInventingTokensOrRefund() throws Exception {
        var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        accounting.reserve(project, batch.id(), explanation, id); accounting.unknown(project, id);
        JsonNode view = view(batch.id()); accounting(view, 1, 316373, 0); assertRequest(view, id, "RUNNING", null);
        JsonNode outcome = first(view).path("attempt").path("outcome");
        assertThat(outcome.path("state").asText()).isEqualTo("UNKNOWN");
        assertThat(outcome.path("actualMicrousd").isNull()).isTrue(); assertThat(outcome.path("usage").isNull()).isTrue();
        assertThat(view.path("stop").path("code").asText()).isEqualTo("OUTPUT_INVALID");
    }

    // 可信零费用和未知费用必须在视图中保持区别。
    @Test void validSettledZeroChargeIsNotTheSameAsUnknown() throws Exception {
        var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        accounting.reserve(project, batch.id(), explanation, id);
        accounting.settle(project, id, LiveCostPolicy.CONTEXT_VERSION, new LiveTokenUsage(0, 0, 0, 0, 0));
        JsonNode view = view(batch.id()); accounting(view, 1, 0, 0);
        JsonNode outcome = first(view).path("attempt").path("outcome");
        assertThat(outcome.path("state").asText()).isEqualTo("SETTLED");
        assertThat(outcome.path("actualMicrousd").isIntegralNumber()).isTrue();
        assertThat(outcome.path("actualMicrousd").asLong()).isZero(); assertThat(outcome.path("usage").isObject()).isTrue();
    }

    @Test void successfulExplanationThenSummaryHaveDistinctExactBindingsAndCombinedSettledAccounting() throws Exception {
        var batch = batch(); UUID first = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        var firstAdmission = accounting.reserve(project, batch.id(), explanation, first);
        accounting.settle(project, first, LiveCostPolicy.CONTEXT_VERSION, KNOWN_USAGE);
        assertThat(requests.finish(first, new AgentRequestView.AgentResult("Synthetic explanation", List.of(), List.of(),
            List.of(), List.of(), "INSUFFICIENT"), null, null, now)).isTrue();
        UUID second = insertLive(batch.id(), summary, "PR_SUMMARY", "RUNNING");
        var secondAdmission = accounting.reserve(project, batch.id(), summary, second);
        accounting.settle(project, second, LiveCostPolicy.CONTEXT_VERSION, KNOWN_USAGE);
        assertThat(requests.finish(second, new AgentRequestView.AgentResult("Synthetic summary", List.of(), List.of(),
            List.of(), List.of(), "INSUFFICIENT"), null, null, now)).isTrue();
        var before = databaseSnapshot(batch.id()); JsonNode view = view(batch.id());
        accounting(view, 2, 0, 546); assertRequest(view, first, "SUCCEEDED", null);
        JsonNode explanationView = first(view), summaryView = view.path("templates").get(1);
        assertThat(explanationView.path("templateRequestId").asText()).isEqualTo(explanation.toString());
        assertThat(summaryView.path("templateRequestId").asText()).isEqualTo(summary.toString());
        assertThat(summaryView.path("purpose").asText()).isEqualTo("PR_SUMMARY");
        exactFields(summaryView.path("request"), "id", "state", "failureCode", "traceId", "createdAt", "updatedAt");
        assertThat(summaryView.path("request").path("id").asText()).isEqualTo(second.toString());
        assertThat(summaryView.path("request").path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(summaryView.path("request").path("failureCode").isNull()).isTrue();
        assertThat(explanationView.path("attempt").path("attemptId").asText()).isEqualTo(firstAdmission.attemptId().toString());
        assertThat(summaryView.path("attempt").path("attemptId").asText()).isEqualTo(secondAdmission.attemptId().toString());
        for (JsonNode template : view.path("templates")) {
            assertThat(template.path("attempt").path("outcome").path("state").asText()).isEqualTo("SETTLED");
            assertThat(template.path("attempt").path("outcome").path("actualMicrousd").asLong()).isEqualTo(273);
        }
        assertThat(view.path("stop").isNull()).isTrue();
        assertThat(view.toString()).doesNotContain("Synthetic explanation", "Synthetic summary", "retryAllowed", "remainingRequests");
        assertThat(databaseSnapshot(batch.id())).isEqualTo(before);
    }

    @Test void failedPreflightWithoutAttemptStillShowsAllocatedRequestAndPermanentBatchStop() throws Exception {
        var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        assertThat(requests.failRunning(id, new AgentRequestView.AgentFailure("MODEL_UNAVAILABLE", "Synthetic preflight failure"), now)).isTrue();
        accounting.stopBatch(project, batch.id(), id, "MODEL_UNAVAILABLE");
        var before = databaseSnapshot(batch.id()); JsonNode view = view(batch.id());
        assertRequest(view, id, "FAILED", "MODEL_UNAVAILABLE"); accounting(view, 0, 0, 0);
        assertThat(first(view).path("attempt").isNull()).isTrue();
        assertThat(view.path("stop").path("requestId").asText()).isEqualTo(id.toString());
        assertThat(view.path("stop").path("code").asText()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(view.path("templates").get(1).path("request").isNull()).isTrue();
        assertThat(databaseSnapshot(batch.id())).isEqualTo(before);
    }

    // 历史事实在关闭、撤销和删 Key 后仍按当前 Project 读取权限可见，无需消费凭据。
    @Test void historyRemainsReadableAfterCloseExpiryRevocationAndKeyRemovalWithoutMetadataRead() throws Exception {
        var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        accounting.reserve(project, batch.id(), explanation, id);
        accounting.settle(project, id, LiveCostPolicy.CONTEXT_VERSION, KNOWN_USAGE);
        assertThat(requests.failRunning(id, new AgentRequestView.AgentFailure("OUTPUT_INVALID", "Synthetic failure"), now)).isTrue();
        accounting.revoke(project, batch.id(), "http://localhost:8083", false); enablements.revoke(project, approval);
        enablements.update(project, enablements.settings(project).revision(), new AgentSettingsUpdate(false, null));
        AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.set(CredentialStatus.absent());
        AgentLiveAccountingIntegrationTest.INVENTORY.clear();
        AgentPersonalEnablementIntegrationTest.TIME.set(now.plusSeconds(90000));
        AgentPersonalEnablementIntegrationTest.REQUIRE_BOUNDED_METADATA.set(true);
        int metadataReads = AgentPersonalEnablementIntegrationTest.BOUNDED_METADATA_READS.get();
        var before = databaseSnapshot(batch.id()); JsonNode view = view(batch.id());
        assertThat(view.path("batch").path("revoked").asBoolean()).isTrue(); accounting(view, 1, 0, 273);
        assertRequest(view, id, "FAILED", "OUTPUT_INVALID");
        assertThat(AgentPersonalEnablementIntegrationTest.BOUNDED_METADATA_READS.get()).isEqualTo(metadataReads);
        assertThat(databaseSnapshot(batch.id())).isEqualTo(before);
    }

    @Test void currentViewerCanReadButForeignAnonymousAndRemovedMembersCannot() throws Exception {
        var batch = batch(); UUID viewer = UUID.randomUUID(), outsider = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'VIEWER',NOW())")
            .param("p", project).param("a", viewer).update();
        mvc.perform(get(path(batch.id())).with(user(viewer.toString()))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(path(batch.id())).with(anonymous())).andExpect(status().isUnauthorized())
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(path(batch.id())).with(user(outsider.toString()))).andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"));
        UUID foreign = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES (:p,:key,'Synthetic other',NOW(),:a)")
            .param("p", foreign).param("key", "p" + foreign.toString().replace("-", "").substring(0, 12))
            .param("a", AgentLiveAccountingIntegrationTest.OWNER).update();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'VIEWER',NOW())")
            .param("p", foreign).param("a", viewer).update();
        mvc.perform(get("/api/v1/projects/" + foreign + "/agent/batches/" + batch.id() + "/execution").with(user(viewer.toString())))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("agent.not_found"))
            .andExpect(header().string("Cache-Control", "no-store"));
        jdbc.sql("DELETE FROM project.project_members WHERE project_id=:p AND actor_id=:a").param("p", project).param("a", viewer).update();
        mvc.perform(get(path(batch.id())).with(user(viewer.toString()))).andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(path(UUID.randomUUID())).with(user(OWNER))).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("agent.not_found")).andExpect(header().string("Cache-Control", "no-store"));
    }

    // 新投影明确拒绝不支持的旧策略，旧元数据接口继续兼容。
    @Test void legacyPolicyIs409OnlyForNewProjectionWhileOriginalMetadataGetStaysUnchanged() throws Exception {
        AgentLiveAccountingIntegrationTest.identity(AgentLiveAccountingIntegrationTest.OWNER);
        var batch = legacy.batch(2, 8400); var before = databaseSnapshot(batch.id());
        mvc.perform(get(path(batch.id())).with(user(OWNER))).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("agent.conflict")).andExpect(header().string("Cache-Control", "no-store"));
        String original = mvc.perform(get(path(batch.id()).replace("/execution", "")).with(user(OWNER))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        JsonNode metadata = mapper.readTree(original);
        exactFields(metadata, "id", "projectId", "enablementId", "approvedBy", "approvedAt", "manifestSha256", "expiresAt", "maxRequests", "maxCostMicrousd", "revoked");
        assertThat(metadata.path("maxCostMicrousd").asLong()).isEqualTo(8400);
        assertThat(databaseSnapshot(batch.id())).isEqualTo(before);
    }

    // 缺少可信用量或总数不一致时不可用，不能补成零值。
    @Test void missingUsageOrInconsistentTotalsFailClosedInsteadOfInventingZero() throws Exception {
        var missing = batch();
        jdbc.sql("DELETE FROM agent.live_batch_usage WHERE batch_id=:b").param("b", missing.id()).update();
        assertUnavailableUnchanged(missing.id());
        var inconsistent = batch();
        jdbc.sql("UPDATE agent.live_batch_usage SET reserved_microusd=1 WHERE batch_id=:b").param("b", inconsistent.id()).update();
        assertUnavailableUnchanged(inconsistent.id());
    }

    @Test void invalidManifestIsUnavailableAndDoesNotLeakOrRewriteTheCorruptRecord() throws Exception {
        var original = batch(); var persisted = ledger.batch(project, original.id()).orElseThrow();
        UUID corruptId = UUID.randomUUID();
        var corruptView = new LiveBatchView(corruptId, project, approval, original.approvedBy(), original.approvedAt(),
            original.manifestSha256(), original.expiresAt(), 2, 700000, false);
        var corruptManifest = new LiveManifest("private-invalid-manifest-version", project, persisted.manifest().templates());
        ledger.insert(new LiveAccountingStore.Batch(corruptView, persisted.deploymentId(), persisted.inventoryId(),
            corruptManifest, persisted.inventoryProof(), LiveCostPolicy.CONTEXT_VERSION));
        assertUnavailableUnchanged(corruptId);
    }

    // 数据库中的任意文本不能被当成受信公开错误类别。
    @Test void arbitraryPersistedFailureCodeIsUnavailableAndNeverEchoedAsTrustedCategory() throws Exception {
        var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        assertThat(requests.failRunning(id, new AgentRequestView.AgentFailure("private-synthetic-failure-code", "private-synthetic-failure-detail"), now)).isTrue();
        assertUnavailableUnchanged(batch.id());
    }

    @Test void extraSlotOutsideManifestAndMismatchedAttemptBindingAreUnavailable() throws Exception {
        var extra = batch(); UUID extraTemplate = insert(null, explanation, "FINDING_EXPLANATION", "FAILED", false);
        insert(extra.id(), extraTemplate, "FINDING_EXPLANATION", "RUNNING", true);
        assertUnavailableUnchanged(extra.id());
        var wrong = batch(); UUID id = insertLive(wrong.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        ledger.insertAttempt(new LiveAccountingStore.Attempt(id, project, wrong.id(), summary, UUID.randomUUID(),
            AgentLiveAccountingIntegrationTest.DEPLOYMENT, now.atZone(ZoneOffset.UTC).toLocalDate(), LiveCostPolicy.CONTEXT_VERSION,
            316373, null, null), approval, AgentPersonalEnablementIntegrationTest.CREDENTIAL,
            enablements.settings(project).revision(), now);
        jdbc.sql("UPDATE agent.live_batch_usage SET attempts=1,reserved_microusd=316373 WHERE batch_id=:b").param("b", wrong.id()).update();
        assertUnavailableUnchanged(wrong.id());
    }

    @Test void invalidOrUnknownUsageFieldsAreNotDisplayedAsTrustedAccounting() throws Exception {
        for (String usage : List.of("{\"inputTokens\":1,\"outputTokens\":0,\"cachedInputTokens\":2,\"totalTokens\":1,\"reasoningTokens\":0}",
                "{\"inputTokens\":1000.1,\"outputTokens\":100.1,\"cachedInputTokens\":500.1,\"totalTokens\":1100.1,\"reasoningTokens\":0.1}",
                "{\"inputTokens\":-0.1,\"outputTokens\":0,\"cachedInputTokens\":0,\"totalTokens\":0,\"reasoningTokens\":0}",
                "{\"inputTokens\":1000,\"outputTokens\":100,\"cachedInputTokens\":500,\"totalTokens\":1100,\"reasoningTokens\":0.1}",
                "{\"inputTokens\":1000,\"outputTokens\":100,\"cachedInputTokens\":500,\"totalTokens\":1100,\"reasoningTokens\":0,\"privateSyntheticField\":\"private-invalid-usage\"}")) {
            var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
            accounting.reserve(project, batch.id(), explanation, id);
            jdbc.sql("INSERT INTO agent.live_outcomes(request_id,state,usage,actual_microusd,created_at) VALUES (:id,'SETTLED',CAST(:usage AS jsonb),273,:at)")
                .param("id", id).param("usage", usage).param("at", java.sql.Timestamp.from(now)).update();
            jdbc.sql("UPDATE agent.live_batch_usage SET reserved_microusd=0,spent_microusd=273 WHERE batch_id=:b").param("b", batch.id()).update();
            assertUnavailableUnchanged(batch.id());
        }
    }

    // 并发未提交结算不能形成混合快照，读取也不应等其写锁。
    @Test void concurrentUncommittedSettlementDoesNotBlockOrExposeMixedLedgerAndOutcome() throws Exception {
        var batch = batch(); UUID id = insertLive(batch.id(), explanation, "FINDING_EXPLANATION", "RUNNING");
        accounting.reserve(project, batch.id(), explanation, id);
        var attempt = ledger.attempt(project, id).orElseThrow();
        CountDownLatch written = new CountDownLatch(1), commit = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                ledger.finish(attempt, KNOWN_USAGE, 273L, now); written.countDown();
                try { if (!commit.await(10, TimeUnit.SECONDS)) throw new AssertionError("Settlement commit was not released"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
            }));
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var observation = executor.submit(() -> view(batch.id()));
            JsonNode beforeCommit = observation.get(3, TimeUnit.SECONDS);
            accounting(beforeCommit, 1, 316373, 0);
            assertThat(first(beforeCommit).path("attempt").path("outcome").isNull()).isTrue();
            commit.countDown(); writer.get(10, TimeUnit.SECONDS);
            JsonNode afterCommit = view(batch.id()); accounting(afterCommit, 1, 0, 273);
            assertThat(first(afterCommit).path("attempt").path("outcome").path("state").asText()).isEqualTo("SETTLED");
        } finally {
            commit.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private LiveBatchView batch() {
        AgentLiveAccountingIntegrationTest.identity(AgentLiveAccountingIntegrationTest.OWNER);
        List<UUID> templates = List.of(summary, explanation); String digest = accounting.preview(project, templates);
        AgentLiveAccountingIntegrationTest.INVENTORY.add(project + ":" + digest);
        return accounting.approve(project, "http://localhost:8083", false,
            new LiveBatchInput(approval, UUID.randomUUID(), templates, digest, now.plusSeconds(3600), 2, 700000, true));
    }

    private UUID insertLive(UUID batch, UUID template, String purpose, String state) {
        return insert(batch, template, purpose, state, true);
    }

    private UUID insert(UUID batch, UUID template, String purpose, String state, boolean live) {
        AgentLiveAccountingIntegrationTest.identity(AgentLiveAccountingIntegrationTest.OWNER);
        var source = requests.find(project, template).orElseThrow(); var old = source.view().bindings(); var input = source.input();
        String prompt = live ? (purpose.equals("PR_SUMMARY") ? LiveAccountingOperations.SUMMARY_PROMPT : LiveAccountingOperations.EXPLANATION_PROMPT)
            : (purpose.equals("PR_SUMMARY") ? "pr-summary-0.1.0" : "finding-explanation-0.1.0");
        var modelInput = new AgentModelPort.ModelInput(purpose, input.schemaVersion(), prompt, input.findingRef(), input.ruleId(),
            input.ruleVersion(), input.severity(), input.message(), input.subject(), input.evidence(), input.documents(), input.findings());
        String digest = "d".repeat(64);
        if (live && (template.equals(explanation) || template.equals(summary))) digest = accounting.requestDigest(project, batch, template);
        var bindings = new AgentRequestView.VersionBindings(old.scanJobId(), old.reportSha256(), old.prHeadRevisionId(), old.findingIds(),
            old.documentVersions(), prompt, live ? LiveCostPolicy.CONTEXT_MODEL_PROFILE : "disabled-or-test-fake-0.1.0",
            old.modelProtocolVersion(), live ? "deepseek-flash" : "deterministic-fake-or-disabled", old.outputSchemaVersion(),
            live ? LiveCostPolicy.CONTEXT_VERSION : "synthetic-0.1.0", digest);
        UUID id = UUID.randomUUID();
        var request = new AgentRequestView(id, project, AgentLiveAccountingIntegrationTest.OWNER, "c".repeat(32), purpose, state,
            bindings, null, live ? null : new AgentRequestView.AgentFailure("MODEL_DISABLED", "Synthetic template"), null, now, now);
        assertThat(requests.insert(new AgentSnapshot(request, modelInput, source.candidates()), "private-synthetic-key-" + id)).isTrue();
        if (live) links.insert(new LiveRequestStore.Link(id, project, batch, template, approval,
            AgentPersonalEnablementIntegrationTest.CREDENTIAL, enablements.settings(project).revision(), now));
        return id;
    }

    private String path(UUID batch) { return "/api/v1/projects/" + project + "/agent/batches/" + batch + "/execution"; }
    private JsonNode view(UUID batch) throws Exception {
        return mapper.readTree(mvc.perform(get(path(batch)).with(user(OWNER))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
    }
    private static JsonNode first(JsonNode view) { return view.path("templates").get(0); }
    private static void accounting(JsonNode view, int attempts, long reserved, long spent) {
        JsonNode accounting = view.path("accounting"); exactFields(accounting, "attemptCount", "reservedMicrousd", "spentMicrousd");
        assertThat(accounting.path("attemptCount").asInt()).isEqualTo(attempts);
        assertThat(accounting.path("reservedMicrousd").asLong()).isEqualTo(reserved);
        assertThat(accounting.path("spentMicrousd").asLong()).isEqualTo(spent);
    }
    private static void assertRequest(JsonNode view, UUID id, String state, String failureCode) {
        JsonNode request = first(view).path("request"); exactFields(request, "id", "state", "failureCode", "traceId", "createdAt", "updatedAt");
        assertThat(request.path("id").asText()).isEqualTo(id.toString()); assertThat(request.path("state").asText()).isEqualTo(state);
        if (failureCode == null) assertThat(request.path("failureCode").isNull()).isTrue();
        else assertThat(request.path("failureCode").asText()).isEqualTo(failureCode);
        assertThat(request.path("traceId").asText()).isEqualTo("c".repeat(32));
    }
    private static void exactFields(JsonNode node, String... expected) {
        List<String> actual = new ArrayList<>(); node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(expected);
    }
    // 不可用场景还核对数据库快照，确保读取没有顺带修复或改写历史。
    private void assertUnavailableUnchanged(UUID batch) throws Exception {
        var before = databaseSnapshot(batch);
        String error = mvc.perform(get(path(batch)).with(user(OWNER))).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("agent.unavailable")).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        assertThat(error).doesNotContain("private-", "SQLException", "Exception", "manifest", "usage", "Synthetic evidence");
        assertThat(databaseSnapshot(batch)).isEqualTo(before);
    }
    private Map<String, String> databaseSnapshot(UUID batch) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String table : List.of("requests", "personal_enablements", "project_settings", "live_batches", "live_request_links",
                "live_template_request_slots", "live_attempts", "live_batch_stops")) {
            snapshot.put(table, jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM agent."
                    + table + " t WHERE project_id=:p").param("p", project).query(String.class).single());
        }
        for (String table : List.of("live_batch_usage", "live_batch_revocations")) {
            snapshot.put(table, jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM agent."
                    + table + " t WHERE batch_id=:b").param("b", batch).query(String.class).single());
        }
        snapshot.put("live_outcomes", jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text "
                + "FROM agent.live_outcomes t WHERE request_id IN (SELECT request_id FROM agent.live_attempts WHERE project_id=:p)")
            .param("p", project).query(String.class).single());
        snapshot.put("enablement_revocations", jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text "
                + "FROM agent.enablement_revocations t WHERE enablement_id IN (SELECT id FROM agent.personal_enablements WHERE project_id=:p)")
            .param("p", project).query(String.class).single());
        snapshot.put("live_budget_usage", jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text "
                + "FROM agent.live_budget_usage t WHERE (scope='PROJECT' AND scope_id=:p) OR (scope='DEPLOYMENT' AND scope_id=:d AND utc_day=:day)")
            .param("p", project).param("d", AgentLiveAccountingIntegrationTest.DEPLOYMENT)
            .param("day", now.atZone(ZoneOffset.UTC).toLocalDate()).query(String.class).single());
        snapshot.put("audit", jdbc.sql("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text "
                + "FROM audit.audit_records t WHERE project_id=:p").param("p", project).query(String.class).single());
        return snapshot;
    }
}
