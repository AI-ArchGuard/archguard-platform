package io.github.aiarchguard.platform;

import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.*;
import static org.assertj.core.api.Assertions.*;

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
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/** 通过真实服务创建并持久化请求，再由夹具将请求置为 RUNNING，以便单独测试记账服务。 */
@SpringBootTest(properties = {"archguard.agent.enabled=true", "archguard.agent.credentials.enabled=true",
    "archguard.agent.credentials.owner-id=11111111-1111-4111-8111-111111111111",
    "archguard.agent.credentials.deployment-id=22222222-2222-4222-8222-222222222222",
    "archguard.agent.credentials.origin=http://localhost:8083", "archguard.agent.credentials.allow-loopback-http=true",
    "spring.datasource.hikari.connection-timeout=1000", "spring.datasource.hikari.validation-timeout=1000",
    "spring.datasource.hikari.data-source-properties.connectTimeout=2",
    "spring.datasource.hikari.data-source-properties.socketTimeout=5",
    "spring.datasource.hikari.data-source-properties.cancelSignalTimeout=1"})
@ActiveProfiles("local-compose")
@Import({DailyControlTestConfiguration.class, AgentLiveAccountingIntegrationTest.Config.class})
abstract class DailyAccountingTestSupport extends AgentLiveDatabaseIntegrationTestSupport {
    static final long R = 316373, PROJECT_LIMIT = 5000000, DEPLOYMENT_LIMIT = 20000000;
    static final String PRICE = LiveCostPolicy.CONTEXT_VERSION;
    static final LiveTokenUsage TRUSTED = new LiveTokenUsage(1000,100,500,1100,0);
    static final AtomicInteger DAYS = new AtomicInteger();
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
    @Autowired LiveAccountingStore legacyMoney;
    JdbcDailyAgentStore store;
    DailyAccountingService service;
    DailyAgentRequestService creation;
    AgentExplanationIntegrationTest.Fixture facts;
    final List<UUID> callbacks = new ArrayList<>();
    List<String> fakeBefore;
    LocalDate originalDay;

    @BeforeAll static void guards() { AgentDailyRequestCreationIntegrationTest.saveAndSetRuntimeGuards(); }
    @AfterAll static void restore() { AgentDailyRequestCreationIntegrationTest.restoreRuntimeGuards(); }
    @BeforeEach void prepareAccounting() {
        resetFixture();
        // 每例使用独立的两个相邻日期，隔离同一部署和数据库中其他测试的日账。
        Instant instant = Instant.parse("2038-01-01T12:00:00Z").plusSeconds(3L * DAYS.incrementAndGet() * 86400);
        TIME.set(instant);
        originalDay = instant.atZone(ZoneOffset.UTC).toLocalDate();
        ENV.setProperty("archguard.agent.daily.evidence.price-catalog-expires-at", instant.plusSeconds(172800).toString());
        for (int i = 0; i < 5; i++) ENV.setProperty("archguard.agent.daily.evidence.sources["+i+"].checked-at", instant.toString());
        AgentLiveAccountingIntegrationTest.identity(OWNER);
        fakeBefore = fakeRows();
        store = new JdbcDailyAgentStore(jdbc, mapper);
        service = freshService(PROJECT_LIMIT, DEPLOYMENT_LIMIT);
        creation = new DailyAgentRequestService(projects, actors, controls, requests, store, jobs, reports,
            findings, documents, audit, traces, mapper, clock, work, new DeepSeekRequestPreparation()::prepare,
            link -> callbacks.add(link.requestId()));
        facts = facts();
    }
    @AfterEach void cleanAccounting() {
        try {
            assertThat(fakeRows()).isEqualTo(fakeBefore);
            assertThat(MODEL_CALLS.get()).isZero();
        } finally { resetFixture(); SecurityContextHolder.clearContext(); org.slf4j.MDC.clear(); }
    }
    List<String> fakeRows() {
        return jdbc.sql("SELECT to_jsonb(b)::text FROM agent.budget_usage b WHERE utc_day BETWEEN :day AND :tomorrow ORDER BY scope,scope_id,utc_day")
            .param("day", originalDay).param("tomorrow", originalDay.plusDays(1)).query(String.class).list();
    }
    DailyAccountingService freshService(long projectCap, long deploymentCap) {
        return new DailyAccountingService(controls, new JdbcDailyAgentStore(jdbc, mapper), requests, work, audit, traces,
            clock, projectCap, deploymentCap);
    }
    AgentExplanationIntegrationTest.Fixture facts() {
        var f = AgentExplanationIntegrationTest.fixture(jdbc);
        assertThat(jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:o,'MAINTAINER',:at)")
            .param("p",f.project()).param("o",OWNER).param("at", Timestamp.from(clock.instant())).update()).isOne();
        assertThat(jdbc.sql("UPDATE scanjob.scan_jobs SET completed_at=:at WHERE id=:id")
            .param("at",Timestamp.from(clock.instant())).param("id",f.job()).update()).isOne();
        return f;
    }
    UUID grant(UUID project, int attempts, long cap) {
        var at = clock.instant();
        var context = controls.context(project);
        assertThat(context.runtimeBinding().deploymentId()).isEqualTo(DEPLOYMENT);
        var digest = context.runtimeBindingDigest();
        var risk = controls.acknowledge(project, UUID.randomUUID().toString(), new DailyRiskInput(
            "0.1.0",digest,at.plusSeconds(1800),true,true,true)).value();
        var grant = controls.approveGrant(project, UUID.randomUUID().toString(), new DailyGrantInput(
            "0.1.0",risk.id(),digest,List.of(DailyPurpose.FINDING_EXPLANATION),attempts,cap,at.plusSeconds(1200),true)).value();
        var settings = controls.updateSettings(project, context.settings().revision(), new DailySettingsInput("0.1.0", true, grant.id()));
        assertThat(settings.modelCallsAvailable()).isTrue();
        return grant.id();
    }
    record Case(UUID p, UUID g, UUID r) {}
    Case ready() { return ready(facts, grant(facts.project(),3,1000000)); }
    DailyRequestInput input(AgentExplanationIntegrationTest.Fixture f, UUID grant) {
        return new DailyRequestInput("0.1.0",grant,true,new DailySelection(DailyPurpose.FINDING_EXPLANATION,
            f.job(),"a".repeat(64),null,List.of(f.finding()),List.of()));
    }
    Case ready(AgentExplanationIntegrationTest.Fixture f, UUID grant) {
        var made = creation.create(f.project(), UUID.randomUUID().toString(), input(f,grant));
        assertThat(made.created()).isTrue();
        var view = made.value().request();
        assertThat(view.state()).isEqualTo("QUEUED");
        var c = new Case(f.project(),grant,view.id());
        assertThat(callbacks).containsOnlyOnce(c.r());
        assertThat(store.findRequest(c.p(),c.r()).orElseThrow().grantId()).isEqualTo(grant);
        assertThat(usage(c).activeRequestId()).isEqualTo(c.r());
        assertThat(jdbc.sql("UPDATE agent.requests SET state='RUNNING',updated_at=:at WHERE project_id=:p AND id=:r AND state='QUEUED'")
            .param("at",Timestamp.from(clock.instant())).param("p",c.p()).param("r",c.r()).update()).isOne();
        assertThat(requests.find(c.p(),c.r()).orElseThrow().view().state()).isEqualTo("RUNNING");
        return c;
    }
    static LongSupplier deadline(int seconds) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        return () -> until - System.nanoTime();
    }
    LiveAccountingOperations.Admission reserve(Case c) {
        return service.reserve(c.p(),c.r(),deadline(5));
    }
    void admitted(Case c) {
        assertThat(reserve(c)).as("valid running daily request must acquire its unique attempt")
            .satisfies(a -> { assertThat(a.newAttempt()).isTrue(); assertThat(a.reservedMicrousd()).isEqualTo(R); });
    }
    DailyAgentStore.Usage usage(Case c) { return store.findGrant(c.p(),c.g()).orElseThrow().usage(); }
    record Money(long reserved,long spent) {
        Money plus(long r,long s) { return new Money(reserved+r,spent+s); }
    }
    Money money(String scope, UUID id, LocalDate day) {
        return jdbc.sql("SELECT reserved_microusd,spent_microusd FROM agent.live_budget_usage WHERE scope=:s AND scope_id=:id AND utc_day=:day")
            .param("s",scope).param("id",id).param("day",day).query((row,n)->new Money(row.getLong(1),row.getLong(2)))
            .optional().orElse(new Money(0,0));
    }
    Money deployment() { return money("DEPLOYMENT",DEPLOYMENT,originalDay); }
    Money project(Case c) { return money("PROJECT",c.p(),originalDay); }
    long count(String table, Case c) {
        return jdbc.sql("SELECT count(*) FROM agent."+table+" WHERE request_id=:r").param("r",c.r()).query(Long.class).single();
    }
    void seed(String scope, UUID id, long spent) {
        assertThat(jdbc.sql("INSERT INTO agent.live_budget_usage(scope,scope_id,utc_day,spent_microusd) VALUES (:s,:id,:day,:spent)")
            .param("s",scope).param("id",id).param("day",originalDay).param("spent",spent).update()).isOne();
    }
    String stopCode(Case c) {
        return jdbc.sql("SELECT code FROM agent.daily_grant_stops WHERE grant_id=:g AND request_id=:r")
            .param("g",c.g()).param("r",c.r()).query(String.class).single();
    }
    void resultPrefix(Case c) {
        store.lockSettings(c.p()); store.lockUsage(c.p(),c.g());
        legacyMoney.lockBudget("DEPLOYMENT", DEPLOYMENT, originalDay);
        legacyMoney.lockBudget("PROJECT", c.p(), originalDay);
        assertThat(jdbc.sql("SELECT id FROM agent.requests WHERE project_id=:p AND id=:r FOR UPDATE")
            .param("p",c.p()).param("r",c.r()).query(UUID.class).single()).isEqualTo(c.r());
    }
    void succeed(Case c) {
        assertThat(requests.finish(c.r(),new AgentRequestView.AgentResult("Synthetic",List.of(),List.of(),List.of(),List.of(),"INSUFFICIENT"),
            null,null,clock.instant())).isTrue();
        service.completeSuccess(c.p(),c.r());
    }
}
