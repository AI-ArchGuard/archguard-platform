package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.agent.infrastructure.DeepSeekRequestPreparation;
import io.github.aiarchguard.platform.agent.persistence.*;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.scanjob.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.lang.reflect.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import io.github.aiarchguard.platform.agentdocument.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实领域夹具的正向创建边界；所有授权和事实均为合成，无模型发送。 */
@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true",
    "spring.datasource.hikari.connection-timeout=1000", "spring.datasource.hikari.validation-timeout=1000",
    "spring.datasource.hikari.data-source-properties.connectTimeout=2",
    "spring.datasource.hikari.data-source-properties.socketTimeout=5",
    "spring.datasource.hikari.data-source-properties.cancelSignalTimeout=1"})
@ActiveProfiles("local-compose")
@Import(DailyControlTestConfiguration.class)
class AgentDailyRequestCreationIntegrationTest extends AgentLiveDatabaseIntegrationTestSupport {
    private static final String REPORT_SHA = "a".repeat(64), HEAD_SHA = "b".repeat(40);
    private static final Map<String, String> ORIGINAL_RUNTIME_PROPERTIES = new LinkedHashMap<>();
    // 仅在本测试类执行期间设置 JVM 安全夹具；包含缺省 null 的原值均在结束时恢复。
    @BeforeAll static void saveAndSetRuntimeGuards() {
        Map<String, String> safe = Map.of("jdk.httpclient.disableRetryConnect", "true",
            "jdk.httpclient.enableAllMethodRetry", "false",
            "jdk.internal.httpclient.disableHostnameVerification", "false", "jdk.httpclient.HttpClient.log", "");
        safe.forEach((key, value) -> { ORIGINAL_RUNTIME_PROPERTIES.put(key, System.getProperty(key)); System.setProperty(key, value); });
    }
    @AfterAll static void restoreRuntimeGuards() {
        ORIGINAL_RUNTIME_PROPERTIES.forEach((key, value) -> {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
        });
        ORIGINAL_RUNTIME_PROPERTIES.clear();
    }
    @Autowired DailyAgentControlService controls;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcLiveDatabaseWork work;
    @Autowired JdbcAgentStore requests;
    @Autowired LiveRequestStore liveLinks;
    @Autowired ObjectMapper mapper;
    @Autowired ProjectAuthorization projects;
    @Autowired CurrentActorProvider actors;
    @Autowired ScanJobOperations jobs;
    @Autowired ReportSubmissionOperations reports;
    @Autowired FindingOperations findings;
    @Autowired DocumentOperations documents;
    @Autowired AuditRecorder audit;
    @Autowired TraceIdProvider traces;
    @Autowired Clock clock;
    private final List<DailyAgentStore.RequestLink> callbacks = new ArrayList<>();
    private List<DeploymentBudget> deploymentBudgetBefore;
    private JdbcDailyAgentStore daily;
    private DailyAgentRequestService service;

    @BeforeEach void prepare() {
        resetFixture(); callbacks.clear(); AgentLiveAccountingIntegrationTest.identity(OWNER);
        deploymentBudgetBefore = deploymentBudgets();
        daily = new JdbcDailyAgentStore(jdbc, mapper);
        service = new DailyAgentRequestService(projects, actors, controls, requests, daily, jobs,
            reports, findings, documents, audit, traces, mapper, clock, work,
            new DeepSeekRequestPreparation()::prepare, link -> {
                // 提交后哨兵只读持久化事实，不排队或调用模型。
                assertThat(work.transaction(() -> TimeUnit.SECONDS.toNanos(5), true,
                    () -> requests.find(link.projectId(), link.requestId()).isPresent())).isTrue();
                callbacks.add(link);
            });
    }
    @AfterEach void clear() {
        resetFixture(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); org.slf4j.MDC.clear();
    }
    @Test void viewerCreatesExplanationFromRealImmutableFindingAndEvidence() {
        verifyCreation(fixture(), DailyPurpose.FINDING_EXPLANATION, null);
    }
    @Test void maintainerCreatesSummaryFromRealCompletedPrRevisionMatch() {
        var base = fixture(); verifyCreation(base, DailyPurpose.PR_SUMMARY, completedPrRevision(base));
    }
    private AgentExplanationIntegrationTest.Fixture fixture() {
        var base = AgentExplanationIntegrationTest.fixture(jdbc);
        // 原夹具创建者不冒充配置 owner，显式添加合成 owner 成员行。
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:o,'MAINTAINER',:at)")
            .param("p", base.project()).param("o", OWNER).param("at", Timestamp.from(NOW)).update();
        jdbc.sql("UPDATE scanjob.scan_jobs SET completed_at=:at WHERE id=:id")
            .param("at", Timestamp.from(NOW)).param("id", base.job()).update();
        return base;
    }
    private void verifyCreation(AgentExplanationIntegrationTest.Fixture base, DailyPurpose purpose, UUID revision) {
        UUID project = base.project();
        var context = assertDoesNotThrow(() -> controls.context(project), "合成上下文必须可读");
        var binding = context.runtimeBinding();
        assertThat(binding).isNotNull();
        assertThat(binding.modelAlias()).isEqualTo("deepseek-flash");
        assertThat(binding.mappingSnapshot()).isEqualTo("DeepSeek-V4.1-Flash-1017");
        assertThat(binding.modelProtocolVersion()).isEqualTo("responses-v1-restricted");
        var risk = assertDoesNotThrow(() -> controls.acknowledge(project, "synthetic-risk", new DailyRiskInput(
            "0.1.0", context.runtimeBindingDigest(), NOW.plusSeconds(1800), true, true, true))).value();
        var grant = assertDoesNotThrow(() -> controls.approveGrant(project, "synthetic-grant", new DailyGrantInput(
            "0.1.0", risk.id(), context.runtimeBindingDigest(), List.of(purpose), 3, 1_000_000L, NOW.plusSeconds(1200), true))).value();
        var settings = assertDoesNotThrow(() -> controls.updateSettings(project, 0, new DailySettingsInput("0.1.0", true, grant.id())));
        assertThat(settings.enabled()).isTrue(); assertThat(settings.modelCallsAvailable()).isTrue();
        assertThat(settings.grantId()).isEqualTo(grant.id());
        if (purpose == DailyPurpose.FINDING_EXPLANATION) {
            assertThat(jdbc.sql("UPDATE project.project_members SET role='VIEWER' WHERE project_id=:p AND actor_id=:o")
                .param("p", project).param("o", OWNER).update()).isOne();
        }
        // 创建前先验证公开领域端口与真实有界事务中的当前授权，单独识别夹具故障。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        LongSupplier remaining = () -> deadline - System.nanoTime();
        assertDoesNotThrow(() -> work.transaction(remaining, false, () -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jobs.get(project, base.job()).status()).isEqualTo(ScanJobStatus.SUCCEEDED);
            assertThat(jobs.get(project, base.job()).completedAt()).isEqualTo(NOW);
            assertThat(jobs.get(project, base.job()).reportSha256()).isEqualTo(REPORT_SHA);
            assertThat(jobs.result(project, base.job()).sha256()).isEqualTo(REPORT_SHA);
            assertThat(jobs.result(project, base.job()).canonicalJson()).isNotEmpty();
            var finding = findings.get(project, base.job(), base.finding());
            assertThat(finding.evidenceIds()).containsExactly(base.evidence()); assertThat(finding.version()).isZero();
            var evidence = findings.evidence(project, base.job(), base.evidence());
            assertThat(evidence.projectId()).isEqualTo(project); assertThat(evidence.jobId()).isEqualTo(base.job());
            assertThat(evidence.kind()).isEqualTo("DEPENDENCY");
            if (revision != null) assertThat(reports.matchesCompletedPrRevision(project, base.job(), REPORT_SHA, revision)).isTrue();
            var usable = controls.requireCurrent(project, grant.id(), purpose.name(), remaining);
            assertThat(usable.id()).isEqualTo(grant.id());
            assertThat(usable.usage()).isEqualTo(new DailyAgentStore.Usage(0, 0, 0, null));
            assertThat(daily.settings(project)).isEqualTo(new DailyAgentStore.Settings(true, settings.revision(), grant.id()));
            return true;
        }), "创建前真实领域与授权夹具必须成立");
        assertThat(callbacks).isEmpty(); assertThat(count("requests", project)).isZero(); assertNoActivity(project);
        long beforeAudit = dailyAudits(project);
        var selection = new DailySelection(purpose, base.job(), REPORT_SHA, revision, List.of(base.finding()), List.of());
        var created = assertDoesNotThrow(() -> service.create(project, "synthetic-create",
            new DailyRequestInput("0.1.0", grant.id(), true, selection)), "有效日常请求创建必须成功");
        // 未实现创建应在上方断言失败；后续持久化断言只会在实现返回后运行。
        assertThat(created.created()).isTrue();
        var view = created.value(); var request = view.request();
        assertThat(view.schemaVersion()).isEqualTo("0.1.0");
        assertThat(request.projectId()).isEqualTo(project); assertThat(request.requesterId()).isEqualTo(OWNER);
        assertThat(request.state()).isEqualTo("QUEUED"); assertThat(request.purpose()).isEqualTo(purpose.name());
        assertThat(request.createdAt()).isEqualTo(NOW);
        assertThat(request.bindings().scanJobId()).isEqualTo(base.job());
        assertThat(request.bindings().reportSha256()).isEqualTo(REPORT_SHA);
        assertThat(request.bindings().prHeadRevisionId()).isEqualTo(revision);
        assertThat(request.bindings().findingIds()).containsExactly(base.finding());
        assertThat(request.bindings().documentVersions()).isEmpty();
        assertThat(request.bindings().promptVersion()).isEqualTo(purpose == DailyPurpose.FINDING_EXPLANATION
            ? binding.explanationPromptVersion() : binding.summaryPromptVersion());
        assertThat(request.bindings().modelId()).isEqualTo(binding.modelAlias());
        assertThat(request.bindings().modelProtocolVersion()).isEqualTo(binding.modelProtocolVersion());
        assertThat(request.bindings().outputSchemaVersion()).isEqualTo(binding.outputSchemaVersion());
        assertThat(request.bindings().priceCatalogVersion()).isEqualTo(binding.priceCatalogVersion());
        assertThat(view.authorization()).isEqualTo(new DailyAuthorization(grant.id(), risk.id(), context.runtimeBindingDigest(), settings.revision()));
        assertThat(view.execution()).isEqualTo(new DailyExecution(null, null, DailyCostState.NOT_ATTEMPTED, null, null));
        assertThat(count("requests", project)).isOne();
        assertThat(requests.find(project, request.id()).orElseThrow().view()).isEqualTo(request);
        assertThat(requests.findByKey(project, OWNER, "synthetic-create").orElseThrow().view()).isEqualTo(request);
        assertThat(count("daily_request_links", project)).isOne();
        var link = daily.findRequest(project, request.id()).orElseThrow();
        assertThat(link.grantId()).isEqualTo(grant.id()); assertThat(link.riskAcknowledgementId()).isEqualTo(risk.id());
        assertThat(link.credentialVersion()).isEqualTo(VERSION); assertThat(link.settingsRevision()).isEqualTo(settings.revision());
        assertThat(link.runtimeBindingDigest()).isEqualTo(context.runtimeBindingDigest());
        assertThat(daily.findGrant(project, grant.id()).orElseThrow().usage()).isEqualTo(new DailyAgentStore.Usage(0, 0, 0, request.id()));
        assertThat(dailyAudits(project)).isEqualTo(beforeAudit + 1); assertThat(callbacks).containsExactly(link);
        assertThat(count("daily_attempts", project)).isZero(); assertNoActivity(project);
    }
    private long count(String table, UUID project) {
        return jdbc.sql("SELECT count(*) FROM agent." + table + " WHERE project_id=:p").param("p", project).query(Long.class).single();
    }
    private long dailyAudits(UUID project) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action LIKE 'agent.daily.%'")
            .param("p", project).query(Long.class).single();
    }
    private void assertNoActivity(UUID project) {
        assertThat(count("live_attempts", project)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_budget_usage WHERE scope_id=:p")
            .param("p", project).query(Long.class).single()).isZero();
        assertThat(deploymentBudgets()).containsExactlyElementsOf(deploymentBudgetBefore);
        assertThat(MODEL_CALLS.get()).isZero();
    }
    // 隔离共享部署的既有历史，逐日比较金额与行集合，禁止新增预算或改写历史。
    private List<DeploymentBudget> deploymentBudgets() {
        return jdbc.sql("SELECT utc_day,reserved_microusd,spent_microusd FROM agent.live_budget_usage "
                + "WHERE scope='DEPLOYMENT' AND scope_id=:d ORDER BY utc_day")
            .param("d", DEPLOYMENT).query((row, index) -> new DeploymentBudget(
                row.getObject("utc_day", java.time.LocalDate.class),
                row.getLong("reserved_microusd"), row.getLong("spent_microusd"))).list();
    }
    private record DeploymentBudget(java.time.LocalDate day, long reservedMicrousd, long spentMicrousd) {}

    private record Selected(AgentExplanationIntegrationTest.Fixture facts, DailyGrantView grant,
                            DailyRequestInput input) { UUID project() { return facts.project(); } }
    private Selected selected(boolean summary, boolean withDocuments) {
        var facts = fixture();
        UUID revision = summary ? completedPrRevision(facts) : null;
        var purpose = summary ? DailyPurpose.PR_SUMMARY : DailyPurpose.FINDING_EXPLANATION;
        var grant = approve(facts.project(), "primary");
        controls.updateSettings(facts.project(), 0, new DailySettingsInput("0.1.0", true, grant.id()));
        List<UUID> ids = withDocuments ? List.of(upload(facts.project(), "first-doc", partialDocument("中文😀")),
            upload(facts.project(), "second-doc", partialDocument("另一条😀"))) : List.of();
        var chosen = new DailySelection(purpose, facts.job(), REPORT_SHA, revision, List.of(facts.finding()), ids);
        return new Selected(facts, grant, new DailyRequestInput("0.1.0", grant.id(), true, chosen));
    }
    private DailyGrantView approve(UUID project, String key) {
        var context = controls.context(project);
        var risk = controls.acknowledge(project, "risk-" + key, new DailyRiskInput("0.1.0",
            context.runtimeBindingDigest(), NOW.plusSeconds(1800), true, true, true)).value();
        return controls.approveGrant(project, "grant-" + key, new DailyGrantInput("0.1.0", risk.id(),
            context.runtimeBindingDigest(), List.of(DailyPurpose.FINDING_EXPLANATION, DailyPurpose.PR_SUMMARY),
            3, 1_000_000, NOW.plusSeconds(1200), true)).value();
    }
    private UUID upload(UUID project, String documentKey, String content) {
        return documents.upload(project, documentKey, UUID.randomUUID().toString(), "text/plain", "synthetic.txt",
            content.getBytes(StandardCharsets.UTF_8)).version().id();
    }
    private String partialDocument(String text) {
        String selected = "archguard.internal-dependency " + text;
        return selected + "x".repeat(1024 - selected.length()) + "unselected appendix ".repeat(90);
    }
    private DailyRequestInput selection(Selected f, DailySelection selected) {
        return new DailyRequestInput("0.1.0", f.grant().id(), true, selected);
    }
    private Creation<DailyRequestView> create(Selected f, String key) {
        return assertDoesNotThrow(() -> service.create(f.project(), key, f.input()), "真实夹具的新请求应成功");
    }
    private void role(UUID project, UUID actor, String role) {
        assertThat(jdbc.sql("UPDATE project.project_members SET role=:role WHERE project_id=:p AND actor_id=:a")
            .param("role", role).param("p", project).param("a", actor).update()).isOne();
    }
    private void committed(DailyAgentStore.RequestLink link) {
        assertThat(work.transaction(() -> TimeUnit.SECONDS.toNanos(5), true,
            () -> requests.find(link.projectId(), link.requestId()).isPresent())).isTrue();
        callbacks.add(link);
    }
    private DailyAgentRequestService service(AgentStore requestPort, DailyAgentStore dailyPort, AuditRecorder recorder,
            LiveDatabaseWork database, boolean forbidResources, Consumer<DailyAgentStore.RequestLink> callback,
            Function<AgentModelPort.ModelInput, byte[]> preparation) {
        return new DailyAgentRequestService(projects, actors, controls, requestPort, dailyPort,
            forbidResources ? forbidden(ScanJobOperations.class, jobs) : jobs,
            forbidResources ? forbidden(ReportSubmissionOperations.class, reports) : reports,
            forbidResources ? forbidden(FindingOperations.class, findings) : findings,
            forbidResources ? forbidden(DocumentOperations.class, documents) : documents,
            recorder, traces, mapper, clock, database, preparation, callback);
    }
    private DailyAgentRequestService replayOnly() {
        return service(requests, daily, audit, work, true, this::committed, input -> {
            throw new AssertionError("历史重放不能重新编码提供方请求");
        });
    }
    // 只围绕本测试需要的真实端口调用放置观察点；未拦截的方法原样委托。
    private interface Intercept { Object call(Method method, Object[] args, Callable<Object> real) throws Throwable; }
    @SuppressWarnings("unchecked")
    private <T> T observing(Class<T> type, T delegate, Intercept hook) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
            hook.call(method, args, () -> {
                try { return method.invoke(delegate, args); }
                catch (InvocationTargetException failure) {
                    if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
                    if (failure.getCause() instanceof Error error) throw error;
                    throw new IllegalStateException(failure.getCause());
                }
            }));
    }
    private <T> T forbidden(Class<T> type, T delegate) {
        return observing(type, delegate, (method, args, real) -> { throw new AssertionError("历史重放不得调用 " + method); });
    }
    private AgentSnapshot snapshot(Selected f, boolean ordinary) {
        return assertDoesNotThrow(() -> DailyCreationTestSnapshots.build(f.project(), OWNER, f.input(),
            f.grant().runtimeBindingDigest(), jobs, reports, findings, documents, mapper, NOW, ordinary));
    }
    private record Effects(long requests, long links, long audits, DailyAgentStore.Usage usage, int callbacks) {}
    private Effects effects(Selected f) {
        return new Effects(count("requests", f.project()), count("daily_request_links", f.project()), dailyAudits(f.project()),
            daily.findGrant(f.project(), f.grant().id()).orElseThrow().usage(), callbacks.size());
    }
    private void unchanged(Selected f, Effects before) {
        assertThat(effects(f)).isEqualTo(before); assertNoActivity(f.project());
        assertThat(count("daily_attempts", f.project())).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"EXPIRED", "REVOKED"})
    void replayPreservesStoredVersionsAliasesAndHashesWithoutResourceOrMetadataReads(String state) {
        var f = selected(false, true); var first = create(f, "history");
        var stored = requests.find(f.project(), first.value().request().id()).orElseThrow();
        var link = daily.findRequest(f.project(), stored.view().id()).orElseThrow();
        var newer = upload(f.project(), "first-doc", partialDocument("后来版本"));
        assertThat(newer).isNotIn(f.input().selection().documentVersionIds());
        if (state.equals("REVOKED")) controls.revokeGrant(f.project(), f.grant().id());
        else TIME.set(NOW.plusSeconds(1201));
        role(f.project(), OWNER, "VIEWER"); var before = effects(f);
        // 元数据消失也不能遮蔽具有当前读取权限的历史请求。
        METADATA.set(null); BOUNDED_ONLY.set(true); var originalBudget = ORIGINAL_BUDGET.get(); var replay = replayOnly();
        var result = assertDoesNotThrow(() -> replay.create(f.project(), "history", f.input()));
        assertThat(result.created()).isFalse(); assertThat(result.value()).isEqualTo(first.value());
        assertThat(ORIGINAL_BUDGET.get()).isSameAs(originalBudget);
        assertThat(requests.find(f.project(), stored.view().id())).contains(stored);
        assertThat(daily.findRequest(f.project(), stored.view().id())).contains(link);
        assertThat(stored.view().bindings().documentVersions()).extracting(AgentRequestView.DocumentVersionBinding::documentVersionId)
            .containsExactlyElementsOf(f.input().selection().documentVersionIds().stream().sorted().toList());
        assertThat(stored.input().documents()).isNotEmpty(); assertThat(stored.candidates()).isNotEmpty();
        unchanged(f, before);
    }
    @Test void reorderedFindingAndDocumentSetsReplayWithoutRebuilding() {
        var f = selected(true, true); UUID other = extraFinding(f.facts());
        var s = f.input().selection();
        var forward = new DailySelection(s.purpose(), s.scanJobId(), s.reportSha256(), s.prHeadRevisionId(),
            List.of(f.facts().finding(), other), s.documentVersionIds());
        var input = selection(f, forward);
        var first = assertDoesNotThrow(() -> service.create(f.project(), "sets", input)); var before = effects(f);
        role(f.project(), OWNER, "VIEWER");
        var reversed = selection(f, new DailySelection(s.purpose(), s.scanJobId(), s.reportSha256(), s.prHeadRevisionId(),
            forward.findingIds().reversed(), forward.documentVersionIds().reversed()));
        var replay = assertDoesNotThrow(() -> replayOnly().create(f.project(), "sets", reversed));
        assertThat(replay.created()).isFalse(); assertThat(replay.value()).isEqualTo(first.value()); unchanged(f, before);
    }
    private UUID extraFinding(AgentExplanationIntegrationTest.Fixture base) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO finding.findings(id,project_id,job_id,scanner_finding_id,fingerprint,rule_id,rule_version,severity,subject_id,message) "
            + "VALUES (:id,:p,:j,:scanner,:fingerprint,'archguard.internal-dependency','0.1.0','high','synthetic','合成😀')")
            .param("id", id).param("p", base.project()).param("j", base.job()).param("scanner", "f" + id)
            .param("fingerprint", id.toString().replace("-", "") + "0".repeat(32)).update();
        jdbc.sql("INSERT INTO finding.finding_evidences(finding_id,evidence_id) VALUES (:f,:e)")
            .param("f", id).param("e", base.evidence()).update(); return id;
    }
    @ParameterizedTest @ValueSource(strings = {"SCAN", "REPORT", "PR", "FINDING", "DOCUMENT", "GRANT"})
    void changedOriginalSelectionConflictsBeforeFreshResourceOrEligibilityChecks(String change) {
        var f = selected(true, true); var other = fixture(); var otherGrant = approve(f.project(), "alternate");
        var later = upload(f.project(), "first-doc", partialDocument("新版本"));
        create(f, "conflict"); var s = f.input().selection(); var before = effects(f);
        var altered = new DailySelection(s.purpose(), change.equals("SCAN") ? other.job() : s.scanJobId(),
            change.equals("REPORT") ? "b".repeat(64) : s.reportSha256(), change.equals("PR") ? UUID.randomUUID() : s.prHeadRevisionId(),
            change.equals("FINDING") ? List.of(other.finding()) : s.findingIds(),
            change.equals("DOCUMENT") ? List.of(later) : s.documentVersionIds());
        var input = new DailyRequestInput("0.1.0", change.equals("GRANT") ? otherGrant.id() : f.grant().id(), true, altered);
        role(f.project(), OWNER, "VIEWER"); METADATA.set(null);
        assertThatThrownBy(() -> replayOnly().create(f.project(), "conflict", input)).isInstanceOf(AgentConflictException.class);
        unchanged(f, before);
    }
    @Test void replayStillRequiresCurrentOwnerAndProjectReadAccess() {
        var f = selected(false, false); create(f, "read-permission"); var before = effects(f);
        AgentLiveAccountingIntegrationTest.identity(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThatThrownBy(() -> replayOnly().create(f.project(), "read-permission", f.input()))
            .isInstanceOf(io.github.aiarchguard.platform.project.ProjectPermissionDeniedException.class);
        AgentLiveAccountingIntegrationTest.identity(OWNER);
        jdbc.sql("DELETE FROM project.project_members WHERE project_id=:p AND actor_id=:o")
            .param("p", f.project()).param("o", OWNER).update();
        assertThatThrownBy(() -> replayOnly().create(f.project(), "read-permission", f.input()))
            .isInstanceOf(io.github.aiarchguard.platform.project.ProjectNotFoundException.class);
        unchanged(f, before);
    }
    @Test void reloadedTypedPayloadAndCandidatesKeepExactCanonicalHashes() {
        var f = selected(false, true); var created = create(f, "hashes");
        var saved = requests.find(f.project(), created.value().request().id()).orElseThrow();
        var link = daily.findRequest(f.project(), saved.view().id()).orElseThrow();
        assertThat(saved.input().findings()).isNull();
        assertThat(saved.input().documents().toString()).contains("中文😀");
        assertThat(saved.candidates()).anySatisfy(c -> assertThat(c.documentVersionId()).isNull());
        assertThat(assertDoesNotThrow(() -> DailySnapshotHashes.sha256(saved.input()))).isEqualTo(link.payloadSha256());
        assertThat(assertDoesNotThrow(() -> DailySnapshotHashes.sha256(saved.candidates()))).isEqualTo(link.candidatesSha256());
        var i = saved.input();
        var changed = new AgentModelPort.ModelInput(i.purpose(), i.schemaVersion(), i.promptVersion(), i.findingRef(), i.ruleId(),
            i.ruleVersion(), i.severity(), i.message() + "变化😀", i.subject(), i.evidence(), i.documents(), i.findings());
        assertThat(assertDoesNotThrow(() -> DailySnapshotHashes.sha256(changed))).isNotEqualTo(link.payloadSha256());
        var reversed = new AgentModelPort.ModelInput(i.purpose(), i.schemaVersion(), i.promptVersion(), i.findingRef(), i.ruleId(),
            i.ruleVersion(), i.severity(), i.message(), i.subject(), i.evidence(), i.documents().reversed(), i.findings());
        assertThat(assertDoesNotThrow(() -> DailySnapshotHashes.sha256(reversed))).isNotEqualTo(link.payloadSha256());
        assertThat(assertDoesNotThrow(() -> DailySnapshotHashes.sha256(saved.candidates().reversed()))).isNotEqualTo(link.candidatesSha256());
        var c = saved.candidates().getFirst(); var changedCandidates = new ArrayList<>(saved.candidates());
        changedCandidates.set(0, new AgentSnapshot.Candidate(c.citationId() + "x", c.source(), c.projectId(), c.scanJobId(),
            c.reportSha256(), c.evidenceId(), c.documentVersionId(), c.contentSha256(), c.fragmentIndex(), c.fragmentSha256(), c.label()));
        assertThat(assertDoesNotThrow(() -> DailySnapshotHashes.sha256(changedCandidates))).isNotEqualTo(link.candidatesSha256());
    }
    @Test void genuinelyDifferentKeyOnOccupiedGrantHasExactCategoryWithoutStopOrOtherEffects() {
        var f = selected(false, false); create(f, "occupied"); var before = effects(f);
        assertThatThrownBy(() -> service.create(f.project(), "genuinely-new", f.input())).isInstanceOf(AgentDailyActionInProgressException.class);
        assertThat(daily.findStop(f.project(), f.grant().id())).isEmpty(); unchanged(f, before);
    }

    @ParameterizedTest @ValueSource(strings = {"SAME", "SELECTION", "GRANT", "BINDING"})
    void realConcurrentMissesRecheckWinnerBeforeValidityOrActiveSlot(String mode) throws Exception {
        var f = selected(false, false); var alternate = approve(f.project(), "concurrent-alternate");
        var incoming = f.input();
        if (mode.equals("SELECTION")) {
            var chosen = f.input().selection();
            incoming = selection(f, new DailySelection(chosen.purpose(), chosen.scanJobId(), "b".repeat(64), null,
                chosen.findingIds(), chosen.documentVersionIds()));
        } else if (mode.equals("GRANT") || mode.equals("BINDING")) {
            if (mode.equals("BINDING")) {
                ENV.setProperty("archguard.agent.daily.runtime-revision", "b".repeat(64));
                alternate = approve(f.project(), "changed-binding");
                ENV.setProperty("archguard.agent.daily.runtime-revision", "a".repeat(64));
                assertThat(alternate.runtimeBindingDigest()).isNotEqualTo(f.grant().runtimeBindingDigest());
            }
            incoming = new DailyRequestInput("0.1.0", alternate.id(), true, f.input().selection());
        }
        final var losingInput = incoming;
        var bothMissed = new CountDownLatch(2); var startLocks = new CountDownLatch(1);
        var winnerLocked = new CountDownLatch(1); var releaseWinner = new CountDownLatch(1);
        var winnerPid = new AtomicInteger(); var role = new ThreadLocal<String>(); var first = new ThreadLocal<Boolean>();
        var requestPort = observing(AgentStore.class, requests, (method, args, real) -> {
            Object value = real.call();
            if (method.getName().equals("findByKey") && first.get() == null) {
                first.set(true); assertThat((Optional<?>) value).as("真实首次查询必须未命中").isEmpty();
                bothMissed.countDown(); await(startLocks);
                if ("loser".equals(role.get())) await(winnerLocked);
            }
            return value;
        });
        var dailyPort = observing(DailyAgentStore.class, daily, (method, args, real) -> {
            Object value = real.call();
            if (method.getName().equals("lockSettings") && "winner".equals(role.get()) && winnerPid.get() == 0) {
                winnerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                winnerLocked.countDown(); await(releaseWinner);
            }
            return value;
        });
        var racing = service(requestPort, dailyPort, audit, work, false, this::committed, new DeepSeekRequestPreparation()::prepare);
        var pool = Executors.newFixedThreadPool(2); List<Future<Creation<DailyRequestView>>> futures = new ArrayList<>();
        long baselineAudit = dailyAudits(f.project());
        try {
            futures.add(pool.submit(() -> raceCall(racing, f, f.input(), "winner", role)));
            futures.add(pool.submit(() -> raceCall(racing, f, losingInput, "loser", role)));
            awaitProgress(bothMissed, futures, "两次真实首次查询均未命中"); startLocks.countDown();
            awaitProgress(winnerLocked, futures, "胜者必须持有真实设置行锁");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
            while (System.nanoTime() < deadline) {
                assertNoEarlyFailure(futures);
                if (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'")
                    .param("pid", winnerPid.get()).query(Long.class).single() > 0) { waiting = true; break; }
                Thread.onSpinWait();
            }
            assertThat(waiting).as("败者实际等待胜者的 PostgreSQL 设置行锁").isTrue();
            assertThat(callbacks).isEmpty(); releaseWinner.countDown();
            var winner = assertDoesNotThrow(() -> futures.getFirst().get(10, TimeUnit.SECONDS), "胜者创建应成功");
            assertThat(winner.created()).isTrue();
            if (List.of("SELECTION", "GRANT", "BINDING").contains(mode)) {
                var failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> futures.get(1).get(10, TimeUnit.SECONDS));
                assertThat(failure.getCause()).isInstanceOf(AgentConflictException.class);
            } else {
                var loser = assertDoesNotThrow(() -> futures.get(1).get(10, TimeUnit.SECONDS), "锁后历史重放应成功");
                assertThat(loser.created()).isFalse(); assertThat(loser.value()).isEqualTo(winner.value());
            }
            assertThat(count("requests", f.project())).isOne(); assertThat(count("daily_request_links", f.project())).isOne();
            assertThat(daily.findGrant(f.project(), f.grant().id()).orElseThrow().usage().activeRequestId()).isEqualTo(winner.value().request().id());
            assertThat(callbacks).hasSize(1); assertThat(callbacks.getFirst().requestId()).isEqualTo(winner.value().request().id());
            assertThat(dailyAudits(f.project())).isEqualTo(baselineAudit + 1);
            assertNoActivity(f.project());
        } finally {
            startLocks.countDown(); releaseWinner.countDown(); winnerLocked.countDown();
            futures.forEach(future -> future.cancel(true)); pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).as("有限清理并发夹具").isTrue();
        }
    }
    private Creation<DailyRequestView> raceCall(DailyAgentRequestService racing, Selected f, DailyRequestInput input,
            String name, ThreadLocal<String> role) {
        AgentLiveAccountingIntegrationTest.identity(OWNER); role.set(name);
        try { return racing.create(f.project(), "race", input); }
        finally { role.remove(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); org.slf4j.MDC.clear(); }
    }
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).as("有限测试同步等待").isTrue(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private void awaitProgress(CountDownLatch latch, List<? extends Future<?>> futures, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (latch.getCount() != 0 && System.nanoTime() < deadline) {
            assertNoEarlyFailure(futures); latch.await(10, TimeUnit.MILLISECONDS);
        }
        assertNoEarlyFailure(futures); assertThat(latch.getCount()).as(description).isZero();
    }
    private void assertNoEarlyFailure(List<? extends Future<?>> futures) {
        for (var future : futures) if (future.isDone())
            assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS), "真实创建必须进入并发观察点；未实现接缝提前失败");
    }
    @ParameterizedTest @ValueSource(strings = {"ORDINARY", "UNASSOCIATED_DEEPSEEK", "LIVE"})
    void crossModeOrMissingDailyAssociationCannotReplay(String mode) {
        var f = selected(false, false); create(f, "occupied-before-mode-conflict");
        var existing = snapshot(f, mode.equals("ORDINARY"));
        assertThat(requests.insert(existing, "other-mode")).isTrue();
        if (mode.equals("LIVE")) seedLiveAssociation(f, existing);
        long auditBefore = dailyAudits(f.project()); var usageBefore = daily.findGrant(f.project(), f.grant().id()).orElseThrow().usage();
        assertThatThrownBy(() -> service.create(f.project(), "other-mode", f.input())).isInstanceOf(AgentConflictException.class);
        assertThat(requests.findByKey(f.project(), OWNER, "other-mode")).contains(existing);
        assertThat(count("requests", f.project())).isEqualTo(mode.equals("LIVE") ? 3 : 2);
        assertThat(count("daily_request_links", f.project())).isOne();
        assertThat(dailyAudits(f.project())).isEqualTo(auditBefore); assertThat(callbacks).hasSize(1);
        assertThat(daily.findGrant(f.project(), f.grant().id()).orElseThrow().usage()).isEqualTo(usageBefore);
        assertNoActivity(f.project());
    }
    private void seedLiveAssociation(Selected f, AgentSnapshot request) {
        var template = snapshot(f, true); assertThat(requests.insert(template, "legacy-template")).isTrue();
        UUID approval = UUID.randomUUID(), batch = UUID.randomUUID();
        jdbc.sql("INSERT INTO agent.personal_enablements(id,project_id,deployment_id,approved_by,approved_at,expires_at,credential_version,acknowledgement) "
            + "VALUES (:id,:p,:d,:o,:at,:expiry,:v,'{}')")
            .param("id", approval).param("p", f.project()).param("d", DEPLOYMENT).param("o", OWNER)
            .param("at", Timestamp.from(NOW)).param("expiry", Timestamp.from(NOW.plusSeconds(1800))).param("v", VERSION).update();
        jdbc.sql("INSERT INTO agent.live_batches(id,project_id,enablement_id,deployment_id,inventory_id,approved_by,approved_at,expires_at,manifest_sha256,manifest,max_requests,max_cost_microusd,inventory_proof) "
            + "VALUES (:id,:p,:approval,:d,:inventory,:o,:at,:expiry,:sha,'{}',2,8400,'{\"schemaVersion\":\"0.1.0\"}')")
            .param("id", batch).param("p", f.project()).param("approval", approval).param("d", DEPLOYMENT)
            .param("inventory", UUID.randomUUID()).param("o", OWNER).param("at", Timestamp.from(NOW))
            .param("expiry", Timestamp.from(NOW.plusSeconds(1200))).param("sha", "c".repeat(64)).update();
        liveLinks.insert(new LiveRequestStore.Link(request.view().id(), f.project(), batch,
            template.view().id(), approval, VERSION, 1, NOW));
        assertThat(liveLinks.find(f.project(), request.view().id())).isPresent();
    }
    @Test void losingRealInsertRereadsCommittedOrdinaryWinnerAndConflictsWithoutPartialDailyRows() {
        var f = selected(false, false); var existing = snapshot(f, true); var inserted = new AtomicBoolean();
        var insertFalse = new AtomicBoolean(); var rereads = new AtomicInteger();
        var delegate = observing(AgentStore.class, requests, (method, args, real) -> {
            if (method.getName().equals("insert") && inserted.compareAndSet(false, true)) {
                assertThat(work.transaction(() -> TimeUnit.SECONDS.toNanos(5), false,
                    () -> requests.insert(existing, (String) args[1]))).isTrue();
                Object result = real.call(); assertThat(result).isEqualTo(false); insertFalse.set(true); return result;
            }
            Object result = real.call();
            if (method.getName().equals("findByKey") && insertFalse.get()) {
                rereads.incrementAndGet(); assertThat(result).isEqualTo(Optional.of(existing));
            }
            return result;
        });
        var contender = service(delegate, daily, audit, work, false, this::committed, new DeepSeekRequestPreparation()::prepare);
        long before = dailyAudits(f.project());
        assertThatThrownBy(() -> contender.create(f.project(), "insert-race", f.input())).isInstanceOf(AgentConflictException.class);
        assertThat(insertFalse.get()).isTrue(); assertThat(rereads.get()).isPositive();
        assertThat(requests.findByKey(f.project(), OWNER, "insert-race")).contains(existing);
        assertThat(count("requests", f.project())).isOne(); assertThat(count("daily_request_links", f.project())).isZero();
        assertThat(dailyAudits(f.project())).isEqualTo(before); assertThat(callbacks).isEmpty();
        assertThat(daily.findGrant(f.project(), f.grant().id()).orElseThrow().usage()).isEqualTo(new DailyAgentStore.Usage(0, 0, 0, null));
        assertNoActivity(f.project());
    }

    @ParameterizedTest @ValueSource(strings = {"EXPIRED", "REVOKED"})
    void thirdTransactionInvalidatesGrantWhileLoserWaitsAndHistoryStillWins(String mode) throws Exception {
        var f = selected(false, false);
        var bothMissed = new CountDownLatch(2); var winnerGate = new CountDownLatch(1); var loserGate = new CountDownLatch(1);
        var winnerLocked = new CountDownLatch(1); var releaseWinner = new CountDownLatch(1);
        var thirdLocked = new CountDownLatch(1); var releaseThird = new CountDownLatch(1);
        var winnerPid = new AtomicInteger(); var thirdPid = new AtomicInteger(); var name = new ThreadLocal<String>();
        var firstRead = new ThreadLocal<Boolean>();
        var requestPort = observing(AgentStore.class, requests, (method, args, real) -> {
            var value = real.call();
            if (method.getName().equals("findByKey") && firstRead.get() == null) {
                firstRead.set(true); assertThat((Optional<?>) value).isEmpty(); bothMissed.countDown();
                await("winner".equals(name.get()) ? winnerGate : loserGate);
            }
            return value;
        });
        var dailyPort = observing(DailyAgentStore.class, daily, (method, args, real) -> {
            var value = real.call();
            if (method.getName().equals("lockSettings") && "winner".equals(name.get()) && winnerPid.get() == 0) {
                winnerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                winnerLocked.countDown(); await(releaseWinner);
            }
            return value;
        });
        var racing = service(requestPort, dailyPort, audit, work, false, this::committed, new DeepSeekRequestPreparation()::prepare);
        var pool = Executors.newFixedThreadPool(3); List<Future<?>> all = new ArrayList<>();
        long baselineAudit = dailyAudits(f.project());
        try {
            var winnerFuture = pool.submit(() -> raceCall(racing, f, f.input(), "winner", name)); all.add(winnerFuture);
            var loserFuture = pool.submit(() -> raceCall(racing, f, f.input(), "loser", name)); all.add(loserFuture);
            awaitProgress(bothMissed, all, "胜败两方必须真实首次未命中"); winnerGate.countDown();
            awaitProgress(winnerLocked, all, "胜者真实持锁");
            var third = pool.submit(() -> {
                AgentLiveAccountingIntegrationTest.identity(OWNER);
                try { return work.transaction(() -> TimeUnit.SECONDS.toNanos(20), false, () -> {
                    thirdPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                    daily.lockSettings(f.project()); daily.lockUsage(f.project(), f.grant().id());
                    if (mode.equals("REVOKED")) controls.revokeGrant(f.project(), f.grant().id());
                    else TIME.set(NOW.plusSeconds(1201));
                    thirdLocked.countDown(); await(releaseThird); return true;
                }); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
            }); all.add(third);
            observedWait(winnerPid, all); releaseWinner.countDown();
            var winner = assertDoesNotThrow(() -> winnerFuture.get(10, TimeUnit.SECONDS)); assertThat(winner.created()).isTrue();
            awaitProgress(thirdLocked, List.of(third, loserFuture), "第三事务接管设置锁并变更授权事实");
            loserGate.countDown(); observedWait(thirdPid, List.of(third, loserFuture));
            releaseThird.countDown(); assertThat(third.get(10, TimeUnit.SECONDS)).isTrue();
            var loser = assertDoesNotThrow(() -> loserFuture.get(10, TimeUnit.SECONDS), "等待撤销/过期后仍应恢复原历史");
            assertThat(loser.created()).isFalse(); assertThat(loser.value()).isEqualTo(winner.value());
            assertThat(count("requests", f.project())).isOne(); assertThat(count("daily_request_links", f.project())).isOne();
            assertThat(callbacks).hasSize(1);
            assertThat(controls.getGrant(f.project(), f.grant().id()).state()).isEqualTo(DailyGrantState.valueOf(mode));
            assertThat(dailyAudits(f.project())).isEqualTo(baselineAudit + (mode.equals("REVOKED") ? 2 : 1));
            assertThat(daily.findGrant(f.project(), f.grant().id()).orElseThrow().usage().activeRequestId()).isEqualTo(winner.value().request().id());
            assertThat(callbacks.getFirst().requestId()).isEqualTo(winner.value().request().id());
            assertNoActivity(f.project());
        } finally {
            winnerGate.countDown(); loserGate.countDown(); releaseWinner.countDown(); releaseThird.countDown();
            all.forEach(future -> future.cancel(true)); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
    private void observedWait(AtomicInteger holder, List<? extends Future<?>> futures) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
        while (System.nanoTime() < until) {
            assertNoEarlyFailure(futures);
            if (holder.get() != 0 && jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'")
                .param("pid", holder.get()).query(Long.class).single() > 0) { waiting = true; break; }
            Thread.onSpinWait();
        }
        assertThat(waiting).as("必须观察实际 PostgreSQL 阻塞，不能用时间推断顺序").isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"AUDIT", "BEFORE_COMMIT"})
    void realTransactionRollbackLeavesNoRequestLinkSlotAuditOrCallback(String failurePoint) {
        var f = selected(false, false); long auditBefore = dailyAudits(f.project()); var reached = new AtomicBoolean();
        AuditRecorder failing = event -> {
            audit.record(event);
            if (event.action().startsWith("agent.daily.")) {
                reached.set(true);
                assertThat(count("requests", f.project())).isOne(); assertThat(count("daily_request_links", f.project())).isOne();
                assertThat(callbacks).isEmpty();
                if (failurePoint.equals("AUDIT")) throw new AgentUnavailableException();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { throw new AgentUnavailableException(); }
                });
            }
        };
        var rollback = service(requests, daily, failing, work, false, this::committed, new DeepSeekRequestPreparation()::prepare);
        assertThatThrownBy(() -> rollback.create(f.project(), "rollback", f.input())).isInstanceOf(AgentUnavailableException.class);
        assertThat(reached.get()).as("必须真正到达写入后的故障点，不能把创建 stub 当作回滚成功").isTrue();
        assertThat(count("requests", f.project())).isZero(); assertThat(count("daily_request_links", f.project())).isZero();
        assertThat(daily.findGrant(f.project(), f.grant().id()).orElseThrow().usage()).isEqualTo(new DailyAgentStore.Usage(0, 0, 0, null));
        assertThat(dailyAudits(f.project())).isEqualTo(auditBefore); assertThat(callbacks).isEmpty(); assertNoActivity(f.project());
    }
    @Test void afterCommitQueueFailureKeepsOriginalAndReplayNeverSchedulesAgain() {
        var f = selected(false, false); var queueCalls = new AtomicInteger();
        var lossy = service(requests, daily, audit, work, false, link -> {
            committed(link); queueCalls.incrementAndGet(); throw new IllegalStateException("synthetic queue response lost");
        }, new DeepSeekRequestPreparation()::prepare);
        // 允许边界传播或处理队列异常；事实必须已提交且回调恰好尝试一次。
        catchThrowable(() -> lossy.create(f.project(), "lost-response", f.input()));
        assertThat(queueCalls.get()).as("必须到达真实提交后哨兵").isOne();
        var stored = requests.findByKey(f.project(), OWNER, "lost-response").orElseThrow(); var before = effects(f);
        var replay = assertDoesNotThrow(() -> lossy.create(f.project(), "lost-response", f.input()));
        assertThat(replay.created()).isFalse(); assertThat(replay.value().request()).isEqualTo(stored.view());
        assertThat(queueCalls.get()).isOne(); unchanged(f, before);
    }
    @Test void creationUsesOneBoundedTransactionAndOriginalRemainingSupplier() {
        var f = selected(false, false); var calls = new AtomicInteger();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); LongSupplier remaining = () -> until - System.nanoTime();
        LiveDatabaseWork bounded = new LiveDatabaseWork() {
            @Override public <T> T transaction(LongSupplier supplied, boolean readOnly, Supplier<T> action) {
                assertThat(supplied).isSameAs(remaining); assertThat(readOnly).isFalse(); calls.incrementAndGet();
                return work.transaction(supplied, readOnly, action);
            }
        };
        BOUNDED_ONLY.set(true);
        var original = service(requests, daily, audit, bounded, false, this::committed, new DeepSeekRequestPreparation()::prepare);
        var result = assertDoesNotThrow(() -> original.create(f.project(), "original-budget", f.input(), remaining));
        assertThat(result.created()).isTrue(); assertThat(calls.get()).isOne(); assertThat(ORIGINAL_BUDGET.get()).isSameAs(remaining);
    }
    @ParameterizedTest @ValueSource(strings = {"SHORT_WHOLE", "MULTIPIECE_WHOLE", "OVERSIZE_ENVELOPE", "INVALID_SELECTION"})
    void invalidProjectionOrFullEnvelopeRejectsBeforeAnyCreationEffect(String mode) {
        var f = selected(false, false); String rule = "archguard.internal-dependency ";
        String content = switch (mode) {
            case "SHORT_WHOLE" -> rule + "短文😀";
            case "MULTIPIECE_WHOLE" -> rule + "x".repeat(1024 - rule.length()) + rule + "尾部";
            case "OVERSIZE_ENVELOPE" -> (rule + "中".repeat(1024 - rule.length())).repeat(3) + "unselected appendix";
            default -> partialDocument("普通");
        };
        UUID document = upload(f.project(), "projection-doc", content);
        var selected = selection(f, new DailySelection(DailyPurpose.FINDING_EXPLANATION, f.facts().job(), REPORT_SHA, null,
            mode.equals("INVALID_SELECTION") ? List.of() : List.of(f.facts().finding()), List.of(document)));
        var fragments = documents.search(f.project(), List.of(document), rule.strip());
        if (mode.equals("SHORT_WHOLE")) assertThat(fragments).hasSize(1);
        if (mode.equals("MULTIPIECE_WHOLE")) {
            assertThat(fragments).hasSize(2);
            assertThat(fragments.stream().mapToInt(fragment -> fragment.content().length()).sum()).isEqualTo(content.length());
        }
        if (mode.equals("OVERSIZE_ENVELOPE")) {
            assertThat(fragments).hasSize(3);
            var payload = assertDoesNotThrow(() -> DailyCreationTestSnapshots.build(f.project(), OWNER, selected,
                f.grant().runtimeBindingDigest(), jobs, reports, findings, documents, mapper, NOW, false));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DeepSeekRequestPreparation().prepare(payload.input()));
        }
        long beforeAudit = dailyAudits(f.project());
        assertThatThrownBy(() -> service.create(f.project(), "reject-projection", selected)).isInstanceOf(AgentInvalidException.class);
        assertThat(count("requests", f.project())).isZero(); assertThat(count("daily_request_links", f.project())).isZero();
        assertThat(count("daily_attempts", f.project())).isZero(); assertThat(dailyAudits(f.project())).isEqualTo(beforeAudit);
        assertThat(callbacks).isEmpty(); assertNoActivity(f.project());
    }
    @ParameterizedTest @ValueSource(strings = {"ACTIVE", "EXHAUSTED", "EXPIRED", "STOPPED", "REVOKED"})
    void realStopAndUsageHistoryPreserveReadReplayAndStatePriority(String expected) {
        var f = selected(false, false); var created = create(f, "stop-history"); UUID request = created.value().request().id();
        if (!expected.equals("ACTIVE")) {
            jdbc.sql("UPDATE agent.daily_grant_usage SET attempts=3 WHERE project_id=:p AND grant_id=:g")
                .param("p", f.project()).param("g", f.grant().id()).update();
        }
        if (List.of("EXPIRED", "STOPPED", "REVOKED").contains(expected)) TIME.set(NOW.plusSeconds(1201));
        if (List.of("STOPPED", "REVOKED").contains(expected)) {
            // 只在真实创建返回后的精确关联上追加合成停止事实，不实现停止写入器。
            jdbc.sql("INSERT INTO agent.daily_grant_stops(grant_id,project_id,request_id,code,created_at) VALUES (:g,:p,:r,'MODEL_TIMEOUT',:at)")
                .param("g", f.grant().id()).param("p", f.project()).param("r", request).param("at", Timestamp.from(TIME.get())).update();
        }
        if (expected.equals("REVOKED")) controls.revokeGrant(f.project(), f.grant().id());
        var grant = controls.getGrant(f.project(), f.grant().id());
        assertThat(grant.state()).isEqualTo(DailyGrantState.valueOf(expected));
        assertThat(controls.context(f.project()).currentGrant().state()).isEqualTo(DailyGrantState.valueOf(expected));
        assertThat(requests.find(f.project(), request)).isPresent(); var before = effects(f);
        var replay = assertDoesNotThrow(() -> replayOnly().create(f.project(), "stop-history", f.input()));
        assertThat(replay.created()).isFalse(); assertThat(replay.value().request()).isEqualTo(created.value().request());
        if (expected.equals("ACTIVE"))
            assertThatThrownBy(() -> service.create(f.project(), "after-stop", f.input())).isInstanceOf(AgentDailyActionInProgressException.class);
        else assertThatThrownBy(() -> service.create(f.project(), "after-stop", f.input())).isInstanceOf(AgentDailyStateInvalidException.class);
        unchanged(f, before);
    }
    @Test void latestControlFixtureKeepsCoherentActiveRequestAndUsesExactSideEffectDeltas() {
        var f = selected(false, false); var created = create(f, "control-active-handoff");
        var stored = requests.find(f.project(), created.value().request().id()).orElseThrow();
        var link = daily.findRequest(f.project(), stored.view().id()).orElseThrow();
        var before = effects(f);
        assertThat(before.usage().activeRequestId()).isEqualTo(link.requestId());
        assertThat(link.grantId()).isEqualTo(f.grant().id());
        controls.revokeGrant(f.project(), f.grant().id());
        var after = effects(f);
        assertThat(after.requests()).isEqualTo(before.requests()); assertThat(after.links()).isEqualTo(before.links());
        assertThat(after.usage()).isEqualTo(before.usage()); assertThat(after.callbacks()).isEqualTo(before.callbacks());
        assertThat(after.audits()).isEqualTo(before.audits() + 1);
        assertThat(requests.find(f.project(), stored.view().id())).contains(stored);
        assertThat(daily.findRequest(f.project(), stored.view().id())).contains(link);
        assertThat(controls.getGrant(f.project(), f.grant().id()).state()).isEqualTo(DailyGrantState.REVOKED);
        assertThat(count("daily_attempts", f.project())).isZero(); assertNoActivity(f.project());
    }

    private UUID completedPrRevision(AgentExplanationIntegrationTest.Fixture base) {
        UUID revision = UUID.randomUUID(), gate = UUID.randomUUID();
        UUID repository = jdbc.sql("SELECT repository_id FROM scanjob.scan_jobs WHERE id=:job").param("job", base.job()).query(UUID.class).single();
        UUID rules = jdbc.sql("SELECT rule_set_version_id FROM scanjob.scan_jobs WHERE id=:job").param("job", base.job()).query(UUID.class).single();
        jdbc.sql("""
            INSERT INTO governance.github_webhook_deliveries(delivery_id,payload_sha256,event_type,action,
                provider_repository_id,project_id,repository_id,event_at,processed_at,disposition,sealed)
            VALUES (:id,:sha,'pull_request','synchronize','123',:project,:repository,NOW(),NOW(),'APPLIED',true)
            """).param("id", revision).param("sha", "1".repeat(64))
            .param("project", base.project()).param("repository", repository).update();
        jdbc.sql("""
            INSERT INTO governance.github_pr_head_revisions(delivery_id,project_id,repository_id,
                external_id,head_sha,target_branch,event_at)
            VALUES (:id,:project,:repository,'27',:head,'main',NOW())
            """).param("id", revision).param("project", base.project())
            .param("repository", repository).param("head", HEAD_SHA).update();
        jdbc.sql("""
            INSERT INTO governance.gate_evaluations(id,project_id,repository_id,target_branch,rule_set_version_id,
                candidate_job_id,idempotency_key,request_sha256,outcome,ci_exit_code,policy_version,
                fingerprint_version,new_count,existing_count,resolved_count,blocked_count,evaluated_at,created_by)
            VALUES (:id,:project,:repository,'main',:rules,:job,:key,:sha,'PASS',0,'0.4.0','0.1.0',
                0,0,0,0,NOW(),:actor)
            """).param("id", gate).param("project", base.project()).param("repository", repository)
            .param("rules", rules).param("job", base.job()).param("key", UUID.randomUUID().toString())
            .param("sha", "2".repeat(64)).param("actor", OWNER).update();
        jdbc.sql("""
            INSERT INTO governance.report_submissions(id,project_id,repository_id,rule_set_version_id,
                provider,provider_repository_id,commit_sha,target_branch,pull_request_external_id,
                pull_request_head_sha,pull_request_base_sha,scanner_version,schema_version,report_sha256,
                request_digest,idempotency_key,scan_job_id,status,gate_evaluation_id,created_by,created_at,completed_at)
            VALUES (:id,:project,:repository,:rules,'github','123',:head,'main','27',:head,:base,
                '0.2.1','0.1.0',:sha,:digest,:key,:job,'COMPLETED',:gate,:actor,NOW(),NOW())
            """).param("id", UUID.randomUUID()).param("project", base.project()).param("repository", repository)
            .param("rules", rules).param("head", HEAD_SHA).param("base", "c".repeat(40))
            .param("sha", REPORT_SHA).param("digest", "3".repeat(64)).param("key", UUID.randomUUID().toString())
            .param("job", base.job()).param("gate", gate).param("actor", OWNER).update();
        return revision;
    }
}
