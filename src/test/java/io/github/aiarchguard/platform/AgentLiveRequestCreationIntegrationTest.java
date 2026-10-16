package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/** Synthetic fixtures and metadata only: creation must never dispatch or consume credentials. */
@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true"})
@AutoConfigureMockMvc
@ActiveProfiles("local-compose")
@Import({AgentLiveRequestCreationIntegrationTest.CreationMetadataConfig.class, AgentLiveAccountingIntegrationTest.Config.class,
    AgentLiveRequestCreationIntegrationTest.Config.class})
class AgentLiveRequestCreationIntegrationTest extends AgentLiveDatabaseIntegrationTestSupport {
    static final UUID OWNER = AgentLiveAccountingIntegrationTest.OWNER;
    static final String ORIGIN = "http://localhost:8083";
    static final String TRACE = "d".repeat(32);
    static final String REPORT = "a".repeat(64);
    static final AtomicBoolean FAIL_LINK = new AtomicBoolean();
    static final AtomicReference<CredentialVersionSource> CREATION_METADATA = new AtomicReference<>();
    static final AtomicReference<LongSupplier> CREATION_METADATA_BUDGET = new AtomicReference<>();
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired AgentOperations agents;
    @Autowired AgentStore requests;
    @Autowired AgentEnablementOperations enablements;
    @Autowired LiveAccountingOperations accounting;
    @Autowired LiveRequestOperations creation;
    @Autowired LiveRequestStore links;
    @Autowired DocumentOperations documents;
    @Autowired UnavailableModel model;
    AgentExplanationIntegrationTest.Fixture fixture;
    UUID project, acknowledgement, template;
    Instant now;

    @BeforeEach void prepare() throws Exception {
        FAIL_LINK.set(false);
        CREATION_METADATA.set(null); CREATION_METADATA_BUDGET.set(null);
        AgentPersonalEnablementIntegrationTest.REQUIRE_BOUNDED_METADATA.set(false);
        model.calls.set(0);
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set(null);
        now = AgentPersonalEnablementIntegrationTest.NOW.plusSeconds(
            AgentLiveAccountingIntegrationTest.DAY.incrementAndGet() * 86400L);
        AgentPersonalEnablementIntegrationTest.TIME.set(now);
        AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.set(new CredentialStatus(
            true, AgentPersonalEnablementIntegrationTest.CREDENTIAL, now));
        AgentLiveAccountingIntegrationTest.INVENTORY.clear();
        AgentLiveAccountingIntegrationTest.INVENTORY_SHA.set("e".repeat(64));
        AgentLiveAccountingIntegrationTest.INVENTORY_REVIEWED_AT.set(now);
        fixture = AgentExplanationIntegrationTest.fixture(jdbc);
        project = fixture.project();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'MAINTAINER',:t)")
            .param("p", project).param("a", OWNER).param("t", Timestamp.from(now)).update();
        jdbc.sql("UPDATE scanjob.scan_jobs SET completed_at=:t WHERE id=:id")
            .param("t", Timestamp.from(now)).param("id", fixture.job()).update();
        identity(OWNER);
        ObjectNode input = mapper.valueToTree(new AgentPersonalEnablementIntegrationTest().input());
        input.put("expiresAt", now.plusSeconds(86400).toString())
            .put("priceCatalogExpiresAt", now.plusSeconds(86400).toString());
        for (var source : input.path("sources")) ((ObjectNode) source).put("checkedAt", now.toString());
        acknowledgement = enablements.approve(project, mapper.treeToValue(input, PersonalEnablementInput.class)).id();
        enablements.update(project, 0, new AgentSettingsUpdate(true, acknowledgement));
        template = template("FINDING_EXPLANATION", null, List.of(), "template");
    }

    @AfterEach void clearIdentity() {
        FAIL_LINK.set(false);
        CREATION_METADATA.set(null); CREATION_METADATA_BUDGET.set(null);
        AgentPersonalEnablementIntegrationTest.REQUIRE_BOUNDED_METADATA.set(false);
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set(null);
        SecurityContextHolder.clearContext();
        org.slf4j.MDC.remove("traceId");
    }

    @Test void createsSeparateQueuedExplanationWithFrozenProjectionAndNoBudgetOrModelCall() throws Exception {
        var batch = batch(template);
        var original = requests.find(project, template).orElseThrow();
        String templateRow = requestRow(template);
        var view = create(batch.id(), template, "live-one");
        assertThat(view.id()).isNotEqualTo(template);
        assertThat(view.requesterId()).isEqualTo(OWNER);
        assertThat(view.traceId()).isEqualTo(TRACE);
        assertThat(view.state()).isEqualTo("QUEUED");
        assertThat(view.result()).isNull();
        assertThat(view.failure()).isNull();
        assertThat(view.usage()).isNull();
        assertThat(view.createdAt()).isEqualTo(now);
        assertThat(view.bindings().modelProfileVersion()).isEqualTo(LiveAccountingOperations.MODEL_PROFILE);
        assertThat(view.bindings().modelId()).isEqualTo("deepseek-flash");
        assertThat(view.bindings().modelProtocolVersion()).isEqualTo("responses-v1-restricted");
        assertThat(view.bindings().priceCatalogVersion()).isEqualTo(LiveCostPolicy.VERSION);
        assertThat(view.bindings().promptVersion()).isEqualTo(LiveAccountingOperations.EXPLANATION_PROMPT);
        assertThat(view.bindings().inputDigest()).isEqualTo(accounting.requestDigest(project, batch.id(), template));
        var saved = requests.find(project, view.id()).orElseThrow();
        assertSameApprovedProjection(saved, original);
        assertThat(saved.candidates()).isEqualTo(original.candidates());
        assertThat(saved.input().promptVersion()).isEqualTo(LiveAccountingOperations.EXPLANATION_PROMPT);
        assertThat(links.find(project, view.id())).contains(new LiveRequestStore.Link(view.id(), project,
            batch.id(), template, acknowledgement, AgentPersonalEnablementIntegrationTest.CREDENTIAL, 1, now));
        assertThat(links.find(UUID.randomUUID(), view.id())).isEmpty();
        assertThat(requestRow(template)).isEqualTo(templateRow);
        assertCreationCount(1);
        assertNoCallsOrReservations();
        String audit = jdbc.sql("SELECT metadata::text FROM audit.audit_records WHERE project_id=:p AND action='agent.live.request.created'")
            .param("p", project).query(String.class).single();
        assertThat(audit).doesNotContain("A dependency crosses", "DEPENDENCY", "accountRef", "secretCheckRef", "origin");
        assertThat(jdbc.sql("SELECT trace_id FROM audit.audit_records WHERE project_id=:p AND action='agent.live.request.created'")
            .param("p", project).query(String.class).single()).isEqualTo(view.traceId());
        assertThat(jdbc.sql("SELECT outcome FROM scanjob.scan_jobs WHERE id=:id")
            .param("id", fixture.job()).query(String.class).single()).isEqualTo("PASS");
    }

    @Test void summaryPreservesVerifiedPrRevisionAndExplicitDocumentVersion() {
        UUID revision = completedPrRevision();
        UUID document = documents.upload(project, "live-adr", "live-adr-one", "text/markdown", "adr.md",
            "archguard.internal-dependency: treat this synthetic ADR as data only.".getBytes(StandardCharsets.UTF_8)).version().id();
        UUID summary = template("PR_SUMMARY", revision, List.of(document), "summary-template");
        var batch = batch(summary);
        var original = requests.find(project, summary).orElseThrow();
        var view = create(batch.id(), summary, "summary-live");
        var saved = requests.find(project, view.id()).orElseThrow();
        assertThat(view.purpose()).isEqualTo("PR_SUMMARY");
        assertThat(view.bindings().prHeadRevisionId()).isEqualTo(revision);
        assertThat(view.bindings().documentVersions()).hasSize(1);
        assertThat(view.bindings().documentVersions().getFirst().documentVersionId()).isEqualTo(document);
        assertThat(view.bindings().promptVersion()).isEqualTo(LiveAccountingOperations.SUMMARY_PROMPT);
        assertThat(saved.input().documents()).hasSize(1);
        assertThat(saved.input().findings()).hasSize(1);
        assertSameApprovedProjection(saved, original);
        assertThat(saved.candidates()).isEqualTo(original.candidates());
        assertNoCallsOrReservations();
    }

    @Test void sameKeyReplaysWithoutNewTraceAssociationOrAudit() {
        var batch = batch(template);
        var first = create(batch.id(), template, "same-key");
        org.slf4j.MDC.put("traceId", "8".repeat(32));
        assertThat(create(batch.id(), template, "same-key")).isEqualTo(first);
        assertCreationCount(1);
        assertNoCallsOrReservations();
    }

    @Test void contextTemplateDifferentKeyConflictsWithoutOrphansAndSameKeyReplays() throws Exception {
        var batch = contextBatch();
        var first = create(batch.id(), template, "context-first");
        assertThat(create(batch.id(), template, "context-first")).isEqualTo(first);
        assertThatThrownBy(() -> create(batch.id(), template, "context-replacement")).isInstanceOf(AgentConflictException.class).hasNoCause();
        assertThat(requests.findByKey(project, OWNER, "context-replacement")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.requests WHERE project_id=:p AND bindings->>'modelProfileVersion'=:profile")
            .param("p", project).param("profile", LiveCostPolicy.CONTEXT_MODEL_PROFILE).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_request_links WHERE project_id=:p").param("p", project).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_template_request_slots WHERE project_id=:p").param("p", project).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.request.created'")
            .param("p", project).query(Integer.class).single()).isOne();
        assertNoCallsOrReservations();
    }

    @Test void boundedCreateDefaultRejectsWithoutInvokingALegacyImplementation() {
        var calls = new AtomicInteger();
        LiveRequestOperations legacy = (p, b, t, k, o, s) -> { calls.incrementAndGet(); throw new AssertionError("No legacy fallback"); };
        assertThatThrownBy(() -> legacy.create(project, project, project, "legacy", ORIGIN, false, () -> 1_000_000_000L))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(calls.get()).isZero();
    }

    @Test void boundedCreatePropagatesTheSameOriginalBudgetToBothCredentialChecks() {
        var batch = batch(template);
        LongSupplier remaining = () -> java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        AgentPersonalEnablementIntegrationTest.REQUIRE_BOUNDED_METADATA.set(true);
        AgentPersonalEnablementIntegrationTest.BOUNDED_METADATA_READS.set(0);
        var first = creation.create(project, batch.id(), template, "bounded-create", ORIGIN, false, remaining);
        assertThat(first.state()).isEqualTo("QUEUED");
        assertThat(AgentPersonalEnablementIntegrationTest.BOUNDED_METADATA_READS.get()).isGreaterThanOrEqualTo(2);
        assertThat(CREATION_METADATA_BUDGET.get()).isSameAs(remaining);
        assertThat(AgentPersonalEnablementIntegrationTest.METADATA_BUDGET.get()).isSameAs(remaining);
        assertCreationCount(1); assertNoCallsOrReservations();
    }

    @Test void boundedCreateRejectsLegacyOnlyCredentialMetadataWithoutUnboundedFallback() {
        var batch = batch(template); var legacyReads = new AtomicInteger();
        CREATION_METADATA.set(() -> { legacyReads.incrementAndGet(); return AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.get(); });
        LongSupplier remaining = () -> java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        assertThatThrownBy(() -> creation.create(project, batch.id(), template, "legacy-metadata", ORIGIN, false, remaining))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(CREATION_METADATA_BUDGET.get()).isSameAs(remaining);
        assertThat(legacyReads.get()).isZero();
        assertCreationCount(0); assertNoCallsOrReservations();
    }

    @Test void boundedCreateDoesNotRenewAnExpiredOriginalBudgetAndRollsBackAllWrites() {
        var batch = batch(template);
        var nanos = new java.util.concurrent.atomic.AtomicLong(java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        LongSupplier remaining = nanos::get;
        CREATION_METADATA.set(new CredentialVersionSource() {
            @Override public CredentialStatus current() { throw new AssertionError("No unbounded fallback"); }
            @Override public CredentialStatus current(LongSupplier budget) {
                assertThat(budget).isSameAs(remaining); nanos.set(0); return AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.get();
            }
        });
        assertThatThrownBy(() -> creation.create(project, batch.id(), template, "expired-original-budget", ORIGIN, false, remaining))
            .isInstanceOf(LiveResponsePort.CallFailure.class).hasMessage("MODEL_TIMEOUT").hasNoCause();
        assertThat(CREATION_METADATA_BUDGET.get()).isSameAs(remaining);
        assertThat(requests.findByKey(project, OWNER, "expired-original-budget")).isEmpty();
        assertCreationCount(0); assertNoCallsOrReservations();
    }

    @Test void differentBatchCannotReuseTheSameKeyAndCannotModifyTheFirstLink() {
        var firstBatch = batch(template);
        var first = create(firstBatch.id(), template, "binding-key");
        var secondBatch = batch(template);
        assertThatThrownBy(() -> create(secondBatch.id(), template, "binding-key")).isInstanceOf(AgentConflictException.class);
        assertThat(links.find(project, first.id()).orElseThrow().batchId()).isEqualTo(firstBatch.id());
        assertCreationCount(1);
        assertNoCallsOrReservations();
    }

    @Test void syntheticRequestKeyCannotBeReinterpretedAsLive() {
        var batch = batch(template);
        assertThatThrownBy(() -> create(batch.id(), template, "template")).isInstanceOf(AgentConflictException.class);
        assertThat(links.find(project, template)).isEmpty();
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void invalidIdempotencyKeysAreRejectedWithoutCreatingRequests() {
        var batch = batch(template);
        for (String key : new String[]{null, "", "has space", "x".repeat(129), "line\nbreak", "非ASCII"}) {
            assertThatThrownBy(() -> create(batch.id(), template, key)).isInstanceOf(AgentInvalidException.class);
        }
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void ownerRoleAndOriginAreEachRequired() {
        var batch = batch(template);
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'MAINTAINER',:t)")
            .param("p", project).param("a", other).param("t", Timestamp.from(now)).update();
        identity(other);
        assertThatThrownBy(() -> create(batch.id(), template, "other-owner"))
            .isInstanceOf(io.github.aiarchguard.platform.project.ProjectPermissionDeniedException.class);
        identity(OWNER);
        assertThatThrownBy(() -> creation.create(project, batch.id(), template, "wrong-origin", "https://untrusted.invalid", true))
            .isInstanceOf(io.github.aiarchguard.platform.agentcredential.CredentialFailure.class);
        jdbc.sql("UPDATE project.project_members SET role='VIEWER' WHERE project_id=:p AND actor_id=:a")
            .param("p", project).param("a", OWNER).update();
        assertThatThrownBy(() -> create(batch.id(), template, "viewer-only"))
            .isInstanceOf(io.github.aiarchguard.platform.project.ProjectPermissionDeniedException.class);
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void anotherAuthorizedProjectCannotConsumeThisBatchOrTemplate() {
        var batch = batch(template);
        var other = AgentExplanationIntegrationTest.fixture(jdbc);
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:a,'MAINTAINER',:t)")
            .param("p", other.project()).param("a", OWNER).param("t", Timestamp.from(now)).update();
        assertThatThrownBy(() -> creation.create(other.project(), batch.id(), template, "cross-project", ORIGIN, false))
            .isInstanceOf(AgentNotFoundException.class);
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void disabledProjectRejectsNewCreationButAnExactReplayDoesNotCreateWork() {
        var batch = batch(template);
        var first = create(batch.id(), template, "disabled-key");
        enablements.update(project, 1, new AgentSettingsUpdate(false, null));
        assertThatThrownBy(() -> create(batch.id(), template, "new-disabled")).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(create(batch.id(), template, "disabled-key")).isEqualTo(first);
        assertThat(requests.find(project, first.id()).orElseThrow().view()).isEqualTo(first);
        assertCreationCount(1);
        assertNoCallsOrReservations();
    }

    @Test void revokedBatchRejectsNewCreationWithoutChangingTemplate() {
        var batch = batch(template);
        String before = requestRow(template);
        accounting.revoke(project, batch.id(), ORIGIN, false);
        assertThatThrownBy(() -> create(batch.id(), template, "revoked-batch")).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertThat(requestRow(template)).isEqualTo(before);
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void revokedEnablementRejectsNewCreation() {
        var batch = batch(template);
        enablements.revoke(project, acknowledgement);
        assertThatThrownBy(() -> create(batch.id(), template, "revoked-approval")).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void rotatedOrDeletedCredentialMetadataRejectsCreationWithoutConsumingAKey() {
        var batch = batch(template);
        for (var status : List.of(new CredentialStatus(true, UUID.randomUUID(), now), CredentialStatus.absent())) {
            AgentPersonalEnablementIntegrationTest.CREDENTIAL_STATE.set(status);
            assertThatThrownBy(() -> create(batch.id(), template, "changed-key")).isInstanceOf(AgentEnablementInvalidStateException.class);
        }
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void exactBatchExpiryRejectsCreation() {
        var batch = batch(template);
        AgentPersonalEnablementIntegrationTest.TIME.set(batch.expiresAt());
        assertThatThrownBy(() -> create(batch.id(), template, "expired-batch")).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void withdrawnAndReplacedInventoryProofCannotCreateRequests() {
        var batch = batch(template);
        AgentLiveAccountingIntegrationTest.INVENTORY.clear();
        assertThatThrownBy(() -> create(batch.id(), template, "withdrawn-proof")).isInstanceOf(AgentEnablementInvalidStateException.class);
        AgentLiveAccountingIntegrationTest.INVENTORY.add(project + ":" + batch.manifestSha256());
        AgentLiveAccountingIntegrationTest.INVENTORY_SHA.set("9".repeat(64));
        assertThatThrownBy(() -> create(batch.id(), template, "changed-proof")).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void currentEvidenceMustStillMatchTheApprovedImmutableProjection() {
        var batch = batch(template);
        jdbc.sql("UPDATE finding.evidences SET start_line=99 WHERE id=:id").param("id", fixture.evidence()).update();
        assertThatThrownBy(() -> create(batch.id(), template, "changed-evidence")).isInstanceOf(AgentEnablementInvalidStateException.class);
        assertCreationCount(0);
        assertNoCallsOrReservations();
    }

    @Test void successAuditFailureRollsBackRequestAndLinkTogether() {
        var batch = batch(template);
        AgentPersonalEnablementIntegrationTest.FAIL_AUDIT_ACTION.set("agent.live.request.created");
        assertThatThrownBy(() -> create(batch.id(), template, "failed-audit")).isInstanceOf(AgentUnavailableException.class);
        assertThat(requests.findByKey(project, OWNER, "failed-audit")).isEmpty();
        assertCreationCount(0);
        assertNoCallsOrReservations();
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.write_rejected'")
            .param("p", project).query(Integer.class).single()).isOne();
    }

    @Test void linkInsertionFailureCannotLeaveAnUnassociatedRequestOrSuccessAudit() {
        var batch = batch(template);
        FAIL_LINK.set(true);
        assertThatThrownBy(() -> create(batch.id(), template, "failed-link")).isInstanceOf(AgentUnavailableException.class);
        assertThat(requests.findByKey(project, OWNER, "failed-link")).isEmpty();
        assertCreationCount(0);
        assertNoCallsOrReservations();
        assertThat(create(batch.id(), template, "failed-link").state()).isEqualTo("QUEUED");
        assertCreationCount(1);
    }

    @Test void concurrentSameKeyCreatesExactlyOneRequestLinkAndSuccessAudit() throws Exception {
        var batch = batch(template);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<AgentRequestView> write = () -> {
            identity(OWNER);
            try {
                barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return create(batch.id(), template, "parallel-key");
            } finally { SecurityContextHolder.clearContext(); org.slf4j.MDC.remove("traceId"); }
        };
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(write);
            var second = executor.submit(write);
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertCreationCount(1);
        assertNoCallsOrReservations();
    }

    private UUID template(String purpose, UUID revision, List<UUID> docs, String key) {
        var view = agents.create(project, key, new CreateAgentRequest(purpose, fixture.job(), REPORT,
            revision, new java.util.ArrayList<>(List.of(fixture.finding())), new java.util.ArrayList<>(docs)));
        assertThat(view.state()).isEqualTo("FAILED");
        assertThat(view.failure().code()).isEqualTo("MODEL_UNAVAILABLE");
        return view.id();
    }

    private LiveBatchView batch(UUID id) {
        String digest = accounting.preview(project, List.of(id));
        AgentLiveAccountingIntegrationTest.INVENTORY.add(project + ":" + digest);
        return accounting.approve(project, ORIGIN, false,
            new LiveBatchInput(acknowledgement, UUID.randomUUID(), List.of(id), digest,
                now.plusSeconds(3600), 2, 8400, true));
    }

    private LiveBatchView contextBatch() throws Exception {
        ObjectNode input = mapper.valueToTree(new AgentPersonalEnablementIntegrationTest().input());
        input.put("schemaVersion", "0.3.0").put("priceCatalogVersion", LiveCostPolicy.CONTEXT_VERSION)
            .put("mappingSnapshot", "deepseek-v4.1-flash-2026-10-15")
            .put("expiresAt", now.plusSeconds(86400).toString()).put("priceCatalogExpiresAt", now.plusSeconds(86400).toString());
        for (var source : input.path("sources")) ((ObjectNode) source).put("checkedAt", now.toString());
        acknowledgement = enablements.approve(project, mapper.treeToValue(input, PersonalEnablementInput.class)).id();
        enablements.update(project, enablements.settings(project).revision(), new AgentSettingsUpdate(true, acknowledgement));
        UUID summary = template("PR_SUMMARY", completedPrRevision(), List.of(), "context-summary");
        List<UUID> templates = List.of(template, summary); String digest = accounting.preview(project, templates);
        AgentLiveAccountingIntegrationTest.INVENTORY.add(project + ":" + digest);
        return accounting.approve(project, ORIGIN, false, new LiveBatchInput(acknowledgement, UUID.randomUUID(), templates, digest,
            now.plusSeconds(3600), 2, 700000, true));
    }

    private AgentRequestView create(UUID batchId, UUID templateId, String key) {
        return creation.create(project, batchId, templateId, key, ORIGIN, false);
    }

    private String requestRow(UUID id) {
        return jdbc.sql("SELECT row_to_json(r)::text FROM agent.requests r WHERE id=:id")
            .param("id", id).query(String.class).single();
    }

    private void assertSameApprovedProjection(AgentSnapshot actual, AgentSnapshot expected) {
        assertThat(actual.view().purpose()).isEqualTo(expected.view().purpose());
        var actualBindings = actual.view().bindings();
        var expectedBindings = expected.view().bindings();
        assertThat(actualBindings.scanJobId()).isEqualTo(expectedBindings.scanJobId());
        assertThat(actualBindings.reportSha256()).isEqualTo(expectedBindings.reportSha256());
        assertThat(actualBindings.prHeadRevisionId()).isEqualTo(expectedBindings.prHeadRevisionId());
        assertThat(actualBindings.findingIds()).isEqualTo(expectedBindings.findingIds());
        assertThat(actualBindings.documentVersions()).isEqualTo(expectedBindings.documentVersions());
        assertThat(actualBindings.outputSchemaVersion()).isEqualTo(expectedBindings.outputSchemaVersion());
        ObjectNode actualInput = mapper.valueToTree(actual.input());
        ObjectNode expectedInput = mapper.valueToTree(expected.input());
        actualInput.remove("promptVersion");
        expectedInput.remove("promptVersion");
        assertThat(actualInput).isEqualTo(expectedInput);
    }

    private void assertCreationCount(int count) {
        assertThat(jdbc.sql("SELECT count(*) FROM agent.requests WHERE project_id=:p AND bindings->>'modelProfileVersion'=:profile")
            .param("p", project).param("profile", LiveAccountingOperations.MODEL_PROFILE).query(Integer.class).single()).isEqualTo(count);
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_request_links WHERE project_id=:p")
            .param("p", project).query(Integer.class).single()).isEqualTo(count);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action='agent.live.request.created'")
            .param("p", project).query(Integer.class).single()).isEqualTo(count);
    }

    private void assertNoCallsOrReservations() {
        assertThat(model.calls.get()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_attempts WHERE project_id=:p")
            .param("p", project).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_budget_usage WHERE scope='PROJECT' AND scope_id=:p")
            .param("p", project).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.budget_usage WHERE scope='PROJECT' AND scope_id=:p")
            .param("p", project).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT coalesce(sum(reserved_microusd+spent_microusd+attempts),0) FROM agent.live_batch_usage u JOIN agent.live_batches b ON b.id=u.batch_id WHERE b.project_id=:p")
            .param("p", project).query(Long.class).single()).isZero();
    }

    private UUID completedPrRevision() {
        UUID revision = UUID.randomUUID(), gate = UUID.randomUUID();
        UUID repository = jdbc.sql("SELECT repository_id FROM scanjob.scan_jobs WHERE id=:id").param("id", fixture.job()).query(UUID.class).single();
        UUID rules = jdbc.sql("SELECT rule_set_version_id FROM scanjob.scan_jobs WHERE id=:id").param("id", fixture.job()).query(UUID.class).single();
        String head = "b".repeat(40);
        jdbc.sql("""
            INSERT INTO governance.github_webhook_deliveries(delivery_id,payload_sha256,event_type,action,
                provider_repository_id,project_id,repository_id,event_at,processed_at,disposition,sealed)
            VALUES (:id,:sha,'pull_request','synchronize','123',:project,:repository,:t,:t,'APPLIED',true)
            """).param("id", revision).param("sha", "1".repeat(64)).param("project", project).param("repository", repository).param("t", Timestamp.from(now)).update();
        jdbc.sql("""
            INSERT INTO governance.github_pr_head_revisions(delivery_id,project_id,repository_id,external_id,head_sha,target_branch,event_at)
            VALUES (:id,:project,:repository,'27',:head,'main',:t)
            """).param("id", revision).param("project", project).param("repository", repository).param("head", head).param("t", Timestamp.from(now)).update();
        jdbc.sql("""
            INSERT INTO governance.gate_evaluations(id,project_id,repository_id,target_branch,rule_set_version_id,
                candidate_job_id,idempotency_key,request_sha256,outcome,ci_exit_code,policy_version,fingerprint_version,
                new_count,existing_count,resolved_count,blocked_count,evaluated_at,created_by)
            VALUES (:id,:project,:repository,'main',:rules,:job,:key,:sha,'PASS',0,'0.4.0','0.1.0',0,0,0,0,:t,:actor)
            """).param("id", gate).param("project", project).param("repository", repository).param("rules", rules)
            .param("job", fixture.job()).param("key", gate.toString()).param("sha", "2".repeat(64)).param("t", Timestamp.from(now)).param("actor", OWNER).update();
        jdbc.sql("""
            INSERT INTO governance.report_submissions(id,project_id,repository_id,rule_set_version_id,provider,
                provider_repository_id,commit_sha,target_branch,pull_request_external_id,pull_request_head_sha,
                pull_request_base_sha,scanner_version,schema_version,report_sha256,request_digest,idempotency_key,
                scan_job_id,status,gate_evaluation_id,created_by,created_at,completed_at)
            VALUES (:id,:project,:repository,:rules,'github','123',:head,'main','27',:head,:base,'0.2.1','0.1.0',
                :sha,:digest,:key,:job,'COMPLETED',:gate,:actor,:t,:t)
            """).param("id", UUID.randomUUID()).param("project", project).param("repository", repository).param("rules", rules)
            .param("head", head).param("base", "c".repeat(40)).param("sha", REPORT).param("digest", "3".repeat(64))
            .param("key", UUID.randomUUID().toString()).param("job", fixture.job()).param("gate", gate).param("actor", OWNER).param("t", Timestamp.from(now)).update();
        return revision;
    }

    private static void identity(UUID actor) { AgentLiveAccountingIntegrationTest.identity(actor); }

    @TestConfiguration static class CreationMetadataConfig extends AgentPersonalEnablementIntegrationTest.Config {
        @Override @Bean @Primary CredentialVersionSource versionSource() {
            var original = super.versionSource();
            return new CredentialVersionSource() {
                @Override public CredentialStatus current() { var source = CREATION_METADATA.get(); return (source == null ? original : source).current(); }
                @Override public CredentialStatus current(LongSupplier remaining) {
                    CREATION_METADATA_BUDGET.set(remaining);
                    var source = CREATION_METADATA.get(); return (source == null ? original : source).current(remaining);
                }
            };
        }
    }

    @TestConfiguration static class Config {
        @Bean @Primary UnavailableModel noModelDispatch() { return new UnavailableModel(); }
        @Bean @Primary LiveRequestStore failingLinkStore(@Qualifier("jdbcLiveRequestStore") LiveRequestStore delegate) {
            return new LiveRequestStore() {
                @Override public void insert(Link link) {
                    if (FAIL_LINK.compareAndSet(true, false)) throw new AgentUnavailableException();
                    delegate.insert(link);
                }
                @Override public Optional<Link> find(UUID projectId, UUID requestId) { return delegate.find(projectId, requestId); }
            };
        }
    }

    static final class UnavailableModel implements AgentModelPort {
        final AtomicInteger calls = new AtomicInteger();
        @Override public boolean syntheticOnly() { return true; }
        @Override public boolean available() { return false; }
        @Override public ModelResponse explain(ModelInput input) {
            calls.incrementAndGet();
            throw new AssertionError("Creation must never dispatch a model");
        }
    }
}
