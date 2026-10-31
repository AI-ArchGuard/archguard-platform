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
