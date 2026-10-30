package io.github.aiarchguard.platform;

import static org.assertj.core.api.Assertions.*;
import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.agent.persistence.JdbcLiveDatabaseWork;
import io.github.aiarchguard.platform.agentcredential.*;
import io.github.aiarchguard.platform.project.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** 真实应用事务与 JDBC 行锁；所有许可、额度和核对日期仅为明确合成夹具，无发送入口。 */
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
class AgentDailyControlIntegrationTest extends AgentLiveDatabaseIntegrationTestSupport {
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
    @Autowired DailyAgentControlService daily;
    @Autowired JdbcClient jdbc;
    @Autowired JdbcLiveDatabaseWork work;
    @Autowired DataSource source;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    UUID project;
    @BeforeEach void prepare() {
        resetFixture();
        project = newProject(); identity(OWNER);
    }
    @AfterEach void clear() { resetFixture(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); org.slf4j.MDC.clear(); }
    void identity(UUID id) { AgentLiveAccountingIntegrationTest.identity(id); }
    UUID newProject() {
        UUID p = UUID.randomUUID();
        jdbc.sql("INSERT INTO project.projects(id,project_key,name,created_at,created_by) VALUES (:p,:key,'Synthetic daily RED',:now,:owner)")
            .param("p", p).param("key", "daily-" + p.toString().substring(0, 12)).param("now", Timestamp.from(NOW)).param("owner", OWNER).update();
        jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:owner,'MAINTAINER',:now)")
            .param("p", p).param("owner", OWNER).param("now", Timestamp.from(NOW)).update(); return p;
    }
    // 首次正向读取必须成为业务断言，不能让未实现异常被 JUnit 误报为夹具错误。
    DailyContext readableContext() {
        var result = new AtomicReference<DailyContext>();
        assertThatCode(() -> result.set(daily.context(project))).as("authorized daily context metadata read must succeed").doesNotThrowAnyException();
        assertThat(result.get()).as("daily context snapshot").isNotNull();
        return result.get();
    }
    DailySettings readableSettings() {
        var result = new AtomicReference<DailySettings>();
        assertThatCode(() -> result.set(daily.settings(project))).as("authorized daily settings metadata read must succeed").doesNotThrowAnyException();
        assertThat(result.get()).as("daily settings snapshot").isNotNull();
        return result.get();
    }
    DailyRiskInput riskInput() { return new DailyRiskInput("0.1.0", readableContext().runtimeBindingDigest(), NOW.plusSeconds(1800), true, true, true); }
    DailyRiskView risk(String key) { return daily.acknowledge(project, key, riskInput()).value(); }
    DailyGrantInput grantInput(DailyRiskView r) {
        return new DailyGrantInput("0.1.0", r.id(), r.runtimeBindingDigest(),
            List.of(DailyPurpose.PR_SUMMARY, DailyPurpose.FINDING_EXPLANATION), 3, 1_000_000L, NOW.plusSeconds(1200), true);
    }
    DailyGrantView grant(String key) { var r = risk("risk-" + key); return daily.approveGrant(project, key, grantInput(r)).value(); }
    DailySettings enable(DailyGrantView g) { return daily.updateSettings(project, 0, new DailySettingsInput("0.1.0", true, g.id())); }
    long count(String table) { return jdbc.sql("SELECT count(*) FROM agent." + table + " WHERE project_id=:p").param("p", project).query(Long.class).single(); }
    void noSpending() {
        for (String t : List.of("requests", "live_attempts")) assertThat(count(t)).as(t).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM agent.live_budget_usage WHERE scope_id=:p").param("p", project).query(Long.class).single()).isZero();
        assertThat(MODEL_CALLS.get()).isZero();
    }
    @Test void defaultsAreIndependentAndRiskThenGrantNeverEnableOrSpend() {
        assertThat(readableSettings()).isEqualTo(new DailySettings(false, 0, null, false, DailyUnavailableReason.PROJECT_DISABLED));
        var context = daily.context(project);
        assertThat(context.currentGrant()).isNull(); assertThat(context.runtimeBindingDigest()).matches("[a-f0-9]{64}");
        assertThat(context.runtimeBinding().mappingSnapshot()).isEqualTo("DeepSeek-V4.1-Flash-1017");
        assertThat(context.runtimeBinding().evidence().sources()).hasSize(5)
            .allSatisfy(s -> assertThat(s.checkedAt()).isEqualTo(Instant.parse("2026-10-30T01:00:00Z")));
        var r = risk("independent"); var g = daily.approveGrant(project, "independent", grantInput(r)).value();
        assertThat(r.acknowledgedAt()).isEqualTo(NOW);
        assertThat(g.state()).isEqualTo(DailyGrantState.ACTIVE); assertThat(g.stop()).isNull();
        assertThat(g.usage()).isEqualTo(new DailyUsage(0, 0, 0, null));
        assertThat(daily.context(project).currentGrant()).isNull(); assertThat(daily.settings(project).revision()).isZero();
        assertThat(count("daily_risk_acknowledgements")).isOne(); assertThat(count("daily_grants")).isOne(); noSpending();
    }
    @Test void settingsRequireExplicitCurrentGrantAndRevisionIncludingSameValueWrites() {
        assertThatThrownBy(() -> daily.updateSettings(project, 0, new DailySettingsInput("0.1.0", true, null))).isInstanceOf(AgentInvalidException.class);
        var g = grant("settings"); var one = enable(g); assertThat(one.revision()).isOne(); assertThat(one.enabled()).isTrue();
        assertThat(daily.context(project).currentGrant().id()).isEqualTo(g.id());
        assertThat(daily.updateSettings(project, 1, new DailySettingsInput("0.1.0", true, g.id())).revision()).isEqualTo(2);
        assertThatThrownBy(() -> daily.updateSettings(project, 1, new DailySettingsInput("0.1.0", false, null))).isInstanceOf(AgentSettingsPreconditionException.class);
        assertThatThrownBy(() -> daily.updateSettings(project, 2, new DailySettingsInput("0.1.0", false, g.id()))).isInstanceOf(AgentInvalidException.class);
        assertThat(daily.updateSettings(project, 2, new DailySettingsInput("0.1.0", false, null)).revision()).isEqualTo(3); noSpending();
    }
    @Test void riskEnvelopeRetainsOriginalBindingAndInputWithoutPromotingActionTimeToEvidence() throws Exception {
        var input = riskInput(); var r = daily.acknowledge(project, "envelope", input).value();
        String json = jdbc.sql("SELECT acknowledgement::text FROM agent.daily_risk_acknowledgements WHERE id=:id").param("id", r.id()).query(String.class).single();
        var envelope = mapper.readTree(json);
        assertThat(envelope.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("schemaVersion", "runtimeBindingDigest", "runtimeBinding", "expiresAt", "sourcesReviewed", "riskAccepted", "materialRightsAccepted");
        assertThat(envelope.path("runtimeBindingDigest").asText()).isEqualTo(input.runtimeBindingDigest());
        assertThat(envelope.at("/runtimeBinding/credentialVersion").asText()).isEqualTo(VERSION.toString());
        assertThat(envelope.at("/runtimeBinding/evidence/sources/0/checkedAt").asText()).isEqualTo("2026-10-30T01:00:00Z");
    }
    @Test void replayComparesOriginalInputBeforeCurrentEligibilityAndDoesNotExtendHistory() {
        var in = riskInput(); var r = daily.acknowledge(project, "replay", in).value(); var gi = grantInput(r);
        var g = daily.approveGrant(project, "replay", gi).value();
        daily.revokeRisk(project, r.id()); daily.revokeGrant(project, g.id());
        TIME.set(NOW.plusSeconds(172800)); METADATA.set(CredentialStatus.absent());
        ENV.setProperty("archguard.agent.daily.runtime-revision", "b".repeat(64));
        var rr = daily.acknowledge(project, "replay", in); var gr = daily.approveGrant(project, "replay", gi);
        assertThat(rr.created()).isFalse(); assertThat(rr.value().id()).isEqualTo(r.id()); assertThat(rr.value().acknowledgedAt()).isEqualTo(r.acknowledgedAt());
        assertThat(gr.created()).isFalse(); assertThat(gr.value().id()).isEqualTo(g.id()); assertThat(gr.value().approvedAt()).isEqualTo(g.approvedAt());
        assertThat(gr.value().state()).isEqualTo(DailyGrantState.REVOKED);
        assertThatThrownBy(() -> daily.acknowledge(project, "replay", new DailyRiskInput("0.1.0", in.runtimeBindingDigest(), in.expiresAt().plusSeconds(1), true, true, true))).isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(() -> daily.approveGrant(project, "replay", new DailyGrantInput("0.1.0", r.id(), gi.runtimeBindingDigest(), gi.allowedPurposes(), 4, gi.maxCostMicrousd(), gi.expiresAt(), true))).isInstanceOf(AgentConflictException.class);
        assertThat(daily.getRisk(project, r.id()).id()).isEqualTo(r.id()); assertThat(daily.getGrant(project, g.id()).id()).isEqualTo(g.id());
        noSpending();
    }
    @Test void reorderedPurposeSetReplaysButDuplicateAndUnknownSchemaDoNotCreateAnotherGrant() {
        var r = risk("set"); var in = grantInput(r); var g = daily.approveGrant(project, "set", in);
        var reversed = new DailyGrantInput("0.1.0", r.id(), in.runtimeBindingDigest(), List.of(DailyPurpose.FINDING_EXPLANATION, DailyPurpose.PR_SUMMARY), 3, 1_000_000, in.expiresAt(), true);
        assertThat(daily.approveGrant(project, "set", reversed).value()).isEqualTo(g.value());
        assertThat(daily.approveGrant(project, "set", reversed).created()).isFalse();
        for (var purposes : List.of(List.<DailyPurpose>of(), List.of(DailyPurpose.PR_SUMMARY, DailyPurpose.PR_SUMMARY))) {
            assertThatThrownBy(() -> daily.approveGrant(project, UUID.randomUUID().toString(), new DailyGrantInput("0.1.0", r.id(), in.runtimeBindingDigest(), purposes, 3, 1_000_000, in.expiresAt(), true))).isInstanceOf(AgentInvalidException.class);
        }
        assertThat(count("daily_grants")).isOne();
    }
    @Test void freshRiskRequiresEveryConfirmationExactDigestAndExplicitBoundedExpiry() {
        String digest = readableContext().runtimeBindingDigest();
        var bad = new ArrayList<DailyRiskInput>();
        bad.add(new DailyRiskInput("0.2.0", digest, NOW.plusSeconds(600), true, true, true));
        bad.add(new DailyRiskInput("0.1.0", digest, null, true, true, true));
        bad.add(new DailyRiskInput("0.1.0", digest, NOW.plusSeconds(600), false, true, true));
        bad.add(new DailyRiskInput("0.1.0", digest, NOW.plusSeconds(600), true, false, true));
        bad.add(new DailyRiskInput("0.1.0", digest, NOW.plusSeconds(600), true, true, false));
        for (var input : bad) assertThatThrownBy(() -> daily.acknowledge(project, UUID.randomUUID().toString(), input)).isInstanceOf(AgentInvalidException.class);
        for (Instant expiry : List.of(NOW, NOW.minusSeconds(1), NOW.plusSeconds(86401), NOW.plusSeconds(604801)))
            assertThatThrownBy(() -> daily.acknowledge(project, UUID.randomUUID().toString(), new DailyRiskInput("0.1.0", digest, expiry, true, true, true))).isInstanceOf(AgentInvalidException.class);
        assertThatThrownBy(() -> daily.acknowledge(project, "wrong-binding", new DailyRiskInput("0.1.0", "f".repeat(64), NOW.plusSeconds(100), true, true, true))).isInstanceOf(AgentDailyStateInvalidException.class);
        assertThat(count("daily_risk_acknowledgements")).isZero();
    }
    @Test void grantCountCostCallsExpiryAndBindingAreNotDefaultsOrInferredConsent() {
        var r = risk("grant-validation"); var in = grantInput(r);
        for (int count : new int[]{0, -1}) assertThatThrownBy(() -> daily.approveGrant(project, UUID.randomUUID().toString(), new DailyGrantInput("0.1.0", r.id(), in.runtimeBindingDigest(), in.allowedPurposes(), count, 316373, in.expiresAt(), true))).isInstanceOf(AgentInvalidException.class);
        for (long cost : new long[]{-1, 0, 316372, 1_050_001, Long.MAX_VALUE}) assertThatThrownBy(() -> daily.approveGrant(project, UUID.randomUUID().toString(), new DailyGrantInput("0.1.0", r.id(), in.runtimeBindingDigest(), in.allowedPurposes(), 3, cost, in.expiresAt(), true))).isInstanceOf(AgentInvalidException.class);
        for (Instant expiry : List.of(NOW, r.expiresAt().plusSeconds(1))) assertThatThrownBy(() -> daily.approveGrant(project, UUID.randomUUID().toString(), new DailyGrantInput("0.1.0", r.id(), in.runtimeBindingDigest(), in.allowedPurposes(), 3, 1_000_000, expiry, true))).isInstanceOf(AgentInvalidException.class);
        assertThatThrownBy(() -> daily.approveGrant(project, "no-consent", new DailyGrantInput("0.1.0", r.id(), in.runtimeBindingDigest(), in.allowedPurposes(), 3, 1_000_000, in.expiresAt(), false))).isInstanceOf(AgentInvalidException.class);
        METADATA.set(new CredentialStatus(true, UUID.randomUUID(), NOW));
        assertThatThrownBy(() -> daily.approveGrant(project, "changed-credential", in)).isInstanceOf(AgentDailyStateInvalidException.class);
        assertThat(count("daily_grants")).isZero();
    }
    @Test void idsAreProjectScopedAndOwnerViewerMaintainerAndOriginAreEnforced() {
        var r = risk("authority"); var g = daily.approveGrant(project, "authority", grantInput(r)).value(); UUID other = newProject();
        assertThatThrownBy(() -> daily.getRisk(other, r.id())).isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> daily.getGrant(other, g.id())).isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> daily.updateSettings(other, 0, new DailySettingsInput("0.1.0", true, g.id()))).isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> daily.revokeRisk(other, r.id())).isInstanceOf(AgentNotFoundException.class);
        assertThatThrownBy(() -> daily.revokeGrant(other, g.id())).isInstanceOf(AgentNotFoundException.class);
        assertThatCode(() -> daily.authorizeControlWrite(project, "http://localhost:8083", false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> daily.authorizeControlWrite(project, "https://other.invalid", true)).isInstanceOf(CredentialFailure.class);
        jdbc.sql("UPDATE project.project_members SET role='VIEWER' WHERE project_id=:p AND actor_id=:o").param("p", project).param("o", OWNER).update();
        assertThat(daily.getRisk(project, r.id()).id()).isEqualTo(r.id());
        assertThatThrownBy(() -> daily.authorizeControlWrite(project, "http://localhost:8083", false)).isInstanceOf(ProjectPermissionDeniedException.class);
        assertThatThrownBy(() -> daily.revokeGrant(project, g.id())).isInstanceOf(ProjectPermissionDeniedException.class);
        UUID otherActor = UUID.randomUUID(); jdbc.sql("INSERT INTO project.project_members(project_id,actor_id,role,created_at) VALUES (:p,:o,'MAINTAINER',:n)").param("p", project).param("o", otherActor).param("n", Timestamp.from(NOW)).update();
        identity(otherActor);
        for (Runnable read : List.<Runnable>of(() -> daily.settings(project), () -> daily.context(project), () -> daily.getRisk(project, r.id()), () -> daily.getGrant(project, g.id())))
            assertThatThrownBy(read::run).isInstanceOf(ProjectPermissionDeniedException.class);
    }
    @Test void revocationClosesOnlyMatchingSelectedGrantOnceAndPreservesUsageAndAudit() {
        var selected = grant("selected"); var unrelated = grant("unrelated"); enable(selected);
        daily.revokeGrant(project, unrelated.id()); assertThat(daily.settings(project).enabled()).isTrue();
        assertThat(daily.settings(project).revision()).isOne();
        var first = daily.revokeRisk(project, selected.riskAcknowledgementId()); var settings = daily.settings(project);
        assertThat(settings.enabled()).isFalse(); assertThat(settings.grantId()).isNull(); assertThat(settings.revision()).isEqualTo(2);
        assertThat(daily.revokeRisk(project, selected.riskAcknowledgementId())).isEqualTo(first);
        daily.revokeGrant(project, selected.id()); daily.revokeGrant(project, selected.id());
        assertThat(daily.settings(project).revision()).isEqualTo(2);
        assertThat(count("daily_grants")).isEqualTo(2); assertThat(daily.getGrant(project, selected.id()).usage()).isEqualTo(selected.usage()); noSpending();
    }
    @Test void auditFailuresRollbackRiskGrantUsageSettingsAndRevocationTogether() {
        var in = riskInput(); FAIL_AUDIT.set(true);
        assertThatThrownBy(() -> daily.acknowledge(project, "audit-risk", in)).isInstanceOf(AgentUnavailableException.class);
        assertThat(count("daily_risk_acknowledgements")).isZero();
        var r = risk("audit"); FAIL_AUDIT.set(true);
        assertThatThrownBy(() -> daily.approveGrant(project, "audit", grantInput(r))).isInstanceOf(AgentUnavailableException.class);
        assertThat(count("daily_grants")).isZero(); assertThat(count("daily_grant_usage")).isZero();
        var g = daily.approveGrant(project, "audit", grantInput(r)).value(); FAIL_AUDIT.set(true);
        assertThatThrownBy(() -> enable(g)).isInstanceOf(AgentUnavailableException.class); assertThat(daily.settings(project).revision()).isZero();
        enable(g); FAIL_AUDIT.set(true);
        assertThatThrownBy(() -> daily.revokeRisk(project, r.id())).isInstanceOf(AgentUnavailableException.class);
        assertThat(daily.getRisk(project, r.id()).revokedAt()).isNull(); assertThat(daily.settings(project).enabled()).isTrue(); assertThat(daily.settings(project).revision()).isOne();
        FAIL_AUDIT.set(true); assertThatThrownBy(() -> daily.revokeGrant(project, g.id())).isInstanceOf(AgentUnavailableException.class);
        assertThat(daily.getGrant(project, g.id()).revokedAt()).isNull(); assertThat(daily.settings(project).revision()).isOne();
    }
    @Test void selectedHistoryPriorityAndInvalidBindingNeverFabricateCurrentDigest() {
        var g = grant("priority"); enable(g); METADATA.set(CredentialStatus.absent());
        var context = daily.context(project); assertThat(context.runtimeReady()).isFalse();
        assertThat(context.runtimeBinding()).isNull(); assertThat(context.runtimeBindingDigest()).isNull();
        assertThat(context.unavailableReason()).isEqualTo(DailyUnavailableReason.RUNTIME_NOT_READY);
        daily.updateSettings(project, 1, new DailySettingsInput("0.1.0", false, null));
        assertThat(daily.context(project).unavailableReason()).isEqualTo(DailyUnavailableReason.PROJECT_DISABLED);
        assertThat(daily.getGrant(project, g.id()).id()).isEqualTo(g.id());
    }
    @Test void requireCurrentUsesCallerBoundedTransactionAndExactOriginalMetadataSupplier() {
        var g = grant("bounded"); enable(g); BOUNDED_ONLY.set(true);
        LongSupplier remaining = () -> TimeUnit.SECONDS.toNanos(5);
        assertThat(work.transaction(remaining, false, () -> {
            // 先由外层真实有界事务持锁；若 validator 另开 REQUIRES_NEW，会等待自己的锁而不能成功。
            jdbc.sql("SELECT project_id FROM agent.daily_project_settings WHERE project_id=:p FOR UPDATE").param("p", project).query(UUID.class).single();
            jdbc.sql("SELECT grant_id FROM agent.daily_grant_usage WHERE grant_id=:g FOR UPDATE").param("g", g.id()).query(UUID.class).single();
            return daily.requireCurrent(project, g.id(), "FINDING_EXPLANATION", remaining);
        }).id()).isEqualTo(g.id());
        assertThat(ORIGINAL_BUDGET.get()).isSameAs(remaining);
        assertThatThrownBy(() -> daily.requireCurrent(project, g.id(), "FINDING_EXPLANATION", remaining)).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> work.transaction(remaining, false, () -> daily.requireCurrent(project, g.id(), "UNKNOWN", remaining))).isInstanceOf(AgentDailyStateInvalidException.class);
        assertThatThrownBy(() -> work.transaction(remaining, false, () -> daily.requireCurrent(project, g.id(), "FINDING_EXPLANATION", () -> 0))).isInstanceOfAny(AgentUnavailableException.class, LiveResponsePort.CallFailure.class);
        noSpending();
    }
    @Test void ownerDowngradedToViewerCanValidateExplanationButNotSummaryOrControls() {
        var g = grant("viewer-purpose"); enable(g);
        jdbc.sql("UPDATE project.project_members SET role='VIEWER' WHERE project_id=:p AND actor_id=:o").param("p", project).param("o", OWNER).update();
        LongSupplier remaining = () -> TimeUnit.SECONDS.toNanos(5);
        assertThat(work.transaction(remaining, false, () -> daily.requireCurrent(project, g.id(), "FINDING_EXPLANATION", remaining)).id()).isEqualTo(g.id());
        assertThatThrownBy(() -> work.transaction(remaining, false, () -> daily.requireCurrent(project, g.id(), "PR_SUMMARY", remaining))).isInstanceOf(ProjectPermissionDeniedException.class);
        assertThatThrownBy(() -> daily.updateSettings(project, 1, new DailySettingsInput("0.1.0", false, null))).isInstanceOf(ProjectPermissionDeniedException.class);
        assertThatThrownBy(() -> daily.authorizeControlWrite(project, "http://localhost:8083", false)).isInstanceOf(ProjectPermissionDeniedException.class);
        assertThat(daily.getGrant(project, g.id()).id()).isEqualTo(g.id()); noSpending();
    }
    @Test void settingsLockWaitCannotExtendGrantExpiryInRequireCurrent() throws Exception {
        var g = grant("wait-expiry"); enable(g); var executor = Executors.newSingleThreadExecutor();
        try (var blocker = source.getConnection()) {
            blocker.setAutoCommit(false); int pid;
            try (var st = blocker.createStatement(); var rs = st.executeQuery("SELECT pg_backend_pid()")) { rs.next(); pid = rs.getInt(1); }
            try (var st = blocker.createStatement(); var rs = st.executeQuery("SELECT project_id FROM agent.daily_project_settings WHERE project_id='" + project + "' FOR UPDATE")) { assertThat(rs.next()).isTrue(); }
            LongSupplier remaining = () -> TimeUnit.SECONDS.toNanos(5);
            var pending = executor.submit(() -> { identity(OWNER); try {
                return catchThrowable(() -> work.transaction(remaining, false, () -> daily.requireCurrent(project, g.id(), "PR_SUMMARY", remaining)));
            } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } });
            try {
                boolean waiting = false; long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() < until) {
                    if (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'").param("pid", pid).query(Long.class).single() > 0) { waiting = true; break; }
                    Thread.onSpinWait();
                }
                assertThat(waiting).as("validator must lock inside caller transaction").isTrue();
                TIME.set(g.expiresAt()); blocker.commit();
                assertThat(pending.get(10, TimeUnit.SECONDS)).isInstanceOf(AgentDailyStateInvalidException.class);
                assertThat(daily.getGrant(project, g.id()).usage()).isEqualTo(g.usage()); noSpending();
            } finally { blocker.rollback(); pending.cancel(true); }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    // 持有真实设置行锁后观测 PostgreSQL 阻塞，才释放；不能用线程启动顺序或 sleep 证明串行。
    @Test void concurrentSettingsWritesObserveActualRowLockAndExactlyOneRevisionWins() throws Exception {
        assertThatCode(() -> daily.updateSettings(project, 0, new DailySettingsInput("0.1.0", false, null)))
            .as("authorized disabled settings update must succeed before concurrency assertions").doesNotThrowAnyException();
        var executor = Executors.newFixedThreadPool(2); var written = new CountDownLatch(1); var release = new CountDownLatch(1); var blockerPid = new AtomicInteger();
        // 外层测试事务只控制提交时点；两次更新均实际调用应用服务，而非直接伪造 revision。
        var winner = executor.submit(() -> { identity(OWNER); try {
            return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                blockerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                var result = daily.updateSettings(project, 1, new DailySettingsInput("0.1.0", false, null)); written.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                return result;
            });
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } });
        Future<String> pending = null;
        try {
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
            pending = executor.submit(() -> { identity(OWNER); try { daily.updateSettings(project, 1, new DailySettingsInput("0.1.0", false, null)); return "updated"; } catch (AgentSettingsPreconditionException stale) { return "stale"; } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
            while (System.nanoTime() < until) {
                if (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'").param("pid", blockerPid.get()).query(Long.class).single() > 0) { waiting = true; break; }
                Thread.onSpinWait();
            }
            assertThat(waiting).as("actual settings row lock contention").isTrue(); assertThat(pending.isDone()).isFalse();
            release.countDown(); assertThat(winner.get(10, TimeUnit.SECONDS).revision()).isEqualTo(2);
            assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo("stale"); assertThat(daily.settings(project).revision()).isEqualTo(2);
        } finally { release.countDown(); winner.cancel(true); if (pending != null) pending.cancel(true); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void concurrentIdenticalKeysConvergeOnOneRiskAndOneGrantUsage() throws Exception {
        var in = riskInput(); var r = race(() -> daily.acknowledge(project, "race", in));
        assertThat(r.get(0).value().id()).isEqualTo(r.get(1).value().id());
        assertThat(r.stream().filter(Creation::created).count()).isOne();
        var gi = grantInput(r.getFirst().value()); var g = race(() -> daily.approveGrant(project, "race", gi));
        assertThat(g.get(0).value().id()).isEqualTo(g.get(1).value().id()); assertThat(g.stream().filter(Creation::created).count()).isOne();
        assertThat(count("daily_risk_acknowledgements")).isOne(); assertThat(count("daily_grants")).isOne(); assertThat(count("daily_grant_usage")).isOne(); noSpending();
    }
    @Test void legacySyntheticApprovalAndSettingsNeverBecomeDailyAuthorization() {
        UUID legacy = UUID.randomUUID();
        jdbc.sql("INSERT INTO agent.personal_enablements(id,project_id,deployment_id,approved_by,approved_at,expires_at,credential_version,acknowledgement) VALUES (:id,:p,:d,:o,:at,:expiry,:v,'{}')")
            .param("id", legacy).param("p", project).param("d", DEPLOYMENT).param("o", OWNER).param("at", Timestamp.from(NOW.minusSeconds(86400))).param("expiry", Timestamp.from(NOW.plusSeconds(3600))).param("v", VERSION).update();
        jdbc.sql("INSERT INTO agent.project_settings(project_id,enabled,enablement_id) VALUES (:p,true,:id)").param("p", project).param("id", legacy).update();
        assertThat(readableSettings().enabled()).isFalse(); assertThat(daily.context(project).currentGrant()).isNull();
        assertThatThrownBy(() -> daily.updateSettings(project, 0, new DailySettingsInput("0.1.0", true, legacy))).isInstanceOf(AgentNotFoundException.class);
        assertThat(count("daily_risk_acknowledgements")).isZero(); assertThat(count("daily_grants")).isZero(); noSpending();
    }
    @Test void grantStatePriorityIncludesExhaustionButActiveSlotAloneIsNotExhausted() {
        var g = grant("state");
        jdbc.sql("UPDATE agent.daily_grant_usage SET active_request_id=:r WHERE grant_id=:g").param("r", UUID.randomUUID()).param("g", g.id()).update();
        assertThat(daily.getGrant(project, g.id()).state()).isEqualTo(DailyGrantState.ACTIVE);
        jdbc.sql("UPDATE agent.daily_grant_usage SET attempts=3 WHERE grant_id=:g").param("g", g.id()).update();
        assertThat(daily.getGrant(project, g.id()).state()).isEqualTo(DailyGrantState.EXHAUSTED);
        TIME.set(NOW.plusSeconds(1200)); assertThat(daily.getGrant(project, g.id()).state()).isEqualTo(DailyGrantState.EXPIRED);
        daily.revokeGrant(project, g.id()); assertThat(daily.getGrant(project, g.id()).state()).isEqualTo(DailyGrantState.REVOKED);
        assertThat(daily.getGrant(project, g.id()).usage().activeRequestId()).isNotNull();
        assertThat(daily.getGrant(project, g.id()).stop()).isNull();
        var second = grantAfterReset("amount-state");
        jdbc.sql("UPDATE agent.daily_grant_usage SET spent_microusd=683628 WHERE grant_id=:g").param("g", second.id()).update();
        assertThat(daily.getGrant(project, second.id()).state()).isEqualTo(DailyGrantState.EXHAUSTED);
    }
    DailyGrantView grantAfterReset(String key) { TIME.set(NOW); return grant(key); }
    @Test void revokingSelectedGrantPreservesInFlightSlotAndCumulativeUsage() {
        var g = grant("in-flight"); enable(g); UUID active = UUID.randomUUID();
        jdbc.sql("UPDATE agent.daily_grant_usage SET attempts=1,reserved_microusd=316373,spent_microusd=7,active_request_id=:r WHERE grant_id=:g").param("r", active).param("g", g.id()).update();
        var first = daily.revokeGrant(project, g.id()); long audits = auditCount();
        assertThat(first.usage()).isEqualTo(new DailyUsage(1, 316373, 7, active));
        assertThat(daily.revokeGrant(project, g.id())).isEqualTo(first); assertThat(auditCount()).isEqualTo(audits);
        assertThat(daily.settings(project).revision()).isEqualTo(2); assertThat(daily.settings(project).enabled()).isFalse();
    }
    long auditCount() { return jdbc.sql("SELECT count(*) FROM audit.audit_records WHERE project_id=:p AND action LIKE 'agent.daily.%'").param("p", project).query(Long.class).single(); }
    @Test void visibleAsciiKeysAreValidatedAndSeparateRiskGrantDomainsRemainIndependent() {
        var input = riskInput();
        for (String key : List.of("", " ", "bad key", "非ASCII", "x".repeat(129), "line\nfeed")) {
            assertThatThrownBy(() -> daily.acknowledge(project, key, input)).isInstanceOf(AgentInvalidException.class);
        }
        String key = "!".repeat(128); var risk = daily.acknowledge(project, key, input).value();
        assertThat(daily.approveGrant(project, key, grantInput(risk)).created()).isTrue();
        assertThat(count("daily_risk_acknowledgements")).isOne(); assertThat(count("daily_grants")).isOne(); noSpending();
    }
    @Test void everyMutableCurrentBindingFactInvalidatesStoredGrantWithoutChangingHistory() {
        var g = grant("binding-permutations"); enable(g);
        LongSupplier remaining = () -> TimeUnit.SECONDS.toNanos(5);
        var facts = new LinkedHashMap<String, String>();
        facts.put("archguard.agent.daily.runtime-revision", "b".repeat(64));
        facts.put("archguard.agent.daily.secret-check-ref", "synthetic-different-secret");
        facts.put("archguard.agent.daily.network-check-ref", "synthetic-different-network");
        facts.put("archguard.agent.daily.evidence.sources[0].version", "synthetic-different-review-v2");
        facts.put("archguard.agent.daily.evidence.sources[0].checked-at", "2026-10-30T00:30:00Z");
        facts.put("archguard.agent.daily.evidence.price-catalog-expires-at", "2026-10-30T04:00:00Z");
        for (var fact : facts.entrySet()) {
            String original = ENV.getProperty(fact.getKey()); ENV.setProperty(fact.getKey(), fact.getValue());
            try {
                assertThatThrownBy(() -> work.transaction(remaining, false, () -> daily.requireCurrent(project, g.id(), "FINDING_EXPLANATION", remaining)))
                    .as(fact.getKey()).isInstanceOf(AgentDailyStateInvalidException.class);
                assertThat(daily.getGrant(project, g.id())).isEqualTo(g); assertThat(daily.getRisk(project, g.riskAcknowledgementId()).id()).isEqualTo(g.riskAcknowledgementId());
            } finally { ENV.setProperty(fact.getKey(), original); }
        }
        METADATA.set(new CredentialStatus(true, UUID.randomUUID(), NOW));
        assertThatThrownBy(() -> work.transaction(remaining, false, () -> daily.requireCurrent(project, g.id(), "FINDING_EXPLANATION", remaining))).isInstanceOf(AgentDailyStateInvalidException.class);
        assertThat(daily.getGrant(project, g.id())).isEqualTo(g); noSpending();
    }
    @Test void expiredOrMalformedCurrentEvidenceFailsClosedAndDoesNotHideHistoricalApproval() {
        var g = grant("evidence-permutations"); enable(g); LongSupplier remaining = () -> TimeUnit.SECONDS.toNanos(5);
        var facts = Map.of("archguard.agent.daily.evidence.price-catalog-expires-at", NOW.toString(),
            "archguard.agent.daily.evidence.sources[0].checked-at", NOW.minusSeconds(604801).toString(),
            "archguard.agent.daily.evidence.sources[1].url", "https://synthetic-invalid.example/",
            "archguard.agent.daily.evidence.sources[2].version", "", "archguard.agent.daily.runtime-revision", "invalid");
        for (var fact : facts.entrySet()) {
            String original = ENV.getProperty(fact.getKey()); ENV.setProperty(fact.getKey(), fact.getValue());
            try {
                assertThatThrownBy(() -> work.transaction(remaining, false, () -> daily.requireCurrent(project, g.id(), "PR_SUMMARY", remaining)))
                    .as(fact.getKey()).isInstanceOf(AgentUnavailableException.class);
                assertThat(daily.getGrant(project, g.id())).isEqualTo(g);
                assertThat(daily.getRisk(project, g.riskAcknowledgementId()).runtimeBindingDigest()).isEqualTo(g.runtimeBindingDigest());
            } finally { ENV.setProperty(fact.getKey(), original); }
        }
        noSpending();
    }
    @Test void syntheticHistoricalModelPromptPriceAndProtocolBindingsNeverAuthorizeCurrentSends() throws Exception {
        var current = readableContext().runtimeBinding(); LongSupplier remaining = () -> TimeUnit.SECONDS.toNanos(5);
        for (String field : List.of("deploymentId", "runtimeRevision", "credentialVersion", "modelAlias", "mappingSnapshot",
                "allowedResponseModels", "modelProtocolVersion", "explanationPromptVersion", "summaryPromptVersion", "outputSchemaVersion",
                "priceCatalogVersion", "secretCheckRef", "networkCheckRef")) {
            var changed = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(current);
            switch (field) {
                case "deploymentId", "credentialVersion" -> changed.put(field, UUID.randomUUID().toString());
                case "runtimeRevision" -> changed.put(field, "b".repeat(64));
                case "allowedResponseModels" -> changed.putArray(field).add("synthetic-historical-model");
                default -> changed.put(field, "synthetic-historical-" + field);
            }
            var historical = mapper.treeToValue(changed, DailyBindingSnapshot.class);
            UUID historicalGrant = insertSyntheticSelectedHistory(historical, "history-" + field);
            assertThatThrownBy(() -> work.transaction(remaining, false, () -> daily.requireCurrent(project, historicalGrant, "FINDING_EXPLANATION", remaining)))
                .as(field).isInstanceOf(AgentDailyStateInvalidException.class);
            var read = daily.getGrant(project, historicalGrant);
            assertThat(read.id()).isEqualTo(historicalGrant); assertThat(read.runtimeBindingDigest()).isEqualTo(bindingDigest(historical));
            assertThat(read.approvedAt()).isEqualTo(NOW); assertThat(read.usage()).isEqualTo(new DailyUsage(0, 0, 0, null));
        }
        noSpending();
    }
    UUID insertSyntheticSelectedHistory(DailyBindingSnapshot binding, String key) throws Exception {
        UUID r = UUID.randomUUID(), g = UUID.randomUUID(); String digest = bindingDigest(binding);
        var envelope = mapper.createObjectNode().put("schemaVersion", "0.1.0").put("runtimeBindingDigest", digest)
            .put("expiresAt", NOW.plusSeconds(1800).toString()).put("sourcesReviewed", true).put("riskAccepted", true).put("materialRightsAccepted", true);
        envelope.set("runtimeBinding", mapper.valueToTree(binding));
        // 明确合成的旧历史只作为失配测试前提；不通过服务批准，更不产生请求/预留。
        jdbc.sql("INSERT INTO agent.daily_risk_acknowledgements(id,project_id,deployment_id,owner_id,idempotency_key,input_digest,runtime_binding_digest,credential_version,data_policy_version,acknowledged_at,expires_at,acknowledgement) VALUES (:r,:p,:d,:o,:key,:input,:digest,:v,:policy,:at,:expiry,CAST(:body AS jsonb))")
            .param("r", r).param("p", project).param("d", binding.deploymentId()).param("o", binding.ownerId()).param("key", key).param("input", "c".repeat(64)).param("digest", digest)
            .param("v", binding.credentialVersion()).param("policy", binding.dataPolicyVersion()).param("at", Timestamp.from(NOW)).param("expiry", Timestamp.from(NOW.plusSeconds(1800))).param("body", mapper.writeValueAsString(envelope)).update();
        jdbc.sql("INSERT INTO agent.daily_grants(id,project_id,deployment_id,owner_id,risk_acknowledgement_id,idempotency_key,input_digest,runtime_binding_digest,credential_version,allowed_purposes,max_attempts,max_cost_microusd,approved_at,expires_at) VALUES (:g,:p,:d,:o,:r,:key,:input,:digest,:v,ARRAY['FINDING_EXPLANATION','PR_SUMMARY'],3,1000000,:at,:expiry)")
            .param("g", g).param("p", project).param("d", binding.deploymentId()).param("o", binding.ownerId()).param("r", r).param("key", key).param("input", "d".repeat(64)).param("digest", digest).param("v", binding.credentialVersion())
            .param("at", Timestamp.from(NOW)).param("expiry", Timestamp.from(NOW.plusSeconds(1200))).update();
        jdbc.sql("INSERT INTO agent.daily_grant_usage(grant_id,project_id) VALUES (:g,:p)").param("g", g).param("p", project).update();
        jdbc.sql("INSERT INTO agent.daily_project_settings(project_id,enabled,grant_id) VALUES (:p,true,:g) ON CONFLICT(project_id) DO UPDATE SET enabled=true,grant_id=EXCLUDED.grant_id,revision=agent.daily_project_settings.revision+1")
            .param("p", project).param("g", g).update();
        return g;
    }
    @Test void concurrentDifferentOriginalInputsOnSameKeyYieldOneWinnerAndOneConflict() throws Exception {
        var original = riskInput(); var other = new DailyRiskInput("0.1.0", original.runtimeBindingDigest(), original.expiresAt().minusSeconds(1), true, true, true);
        var chosen = new AtomicInteger();
        List<Object> risks = race(() -> { try { return daily.acknowledge(project, "mixed-risk", chosen.getAndIncrement() == 0 ? original : other); } catch (AgentConflictException conflict) { return conflict; } });
        assertThat(risks.stream().filter(Creation.class::isInstance).count()).isOne(); assertThat(risks.stream().filter(AgentConflictException.class::isInstance).count()).isOne();
        var risk = (DailyRiskView) ((Creation<?>) risks.stream().filter(Creation.class::isInstance).findFirst().orElseThrow()).value();
        var first = grantInput(risk); var second = new DailyGrantInput("0.1.0", risk.id(), first.runtimeBindingDigest(), first.allowedPurposes(), 4, first.maxCostMicrousd(), first.expiresAt(), true); chosen.set(0);
        List<Object> grants = race(() -> { try { return daily.approveGrant(project, "mixed-grant", chosen.getAndIncrement() == 0 ? first : second); } catch (AgentConflictException conflict) { return conflict; } });
        assertThat(grants.stream().filter(Creation.class::isInstance).count()).isOne(); assertThat(grants.stream().filter(AgentConflictException.class::isInstance).count()).isOne();
        assertThat(count("daily_risk_acknowledgements")).isOne(); assertThat(count("daily_grants")).isOne(); assertThat(count("daily_grant_usage")).isOne();
        assertThat(auditCount()).isEqualTo(2); noSpending();
    }
    @Test void riskRevocationSerializesAgainstGrantApprovalOnObservedSettingsLock() throws Exception {
        var r = risk("revoke-approval"); var input = grantInput(r);
        var failure = competeWithHeldRevocation(() -> daily.revokeRisk(project, r.id()), () -> daily.approveGrant(project, "after-revoke", input));
        assertThat(failure).isInstanceOf(AgentDailyStateInvalidException.class);
        assertThat(daily.getRisk(project, r.id()).revokedAt()).isEqualTo(NOW);
        assertThat(count("daily_grants")).isZero(); assertThat(count("daily_grant_usage")).isZero(); noSpending();
    }
    @Test void grantRevocationSerializesAgainstEnablementOnObservedSettingsLock() throws Exception {
        var g = grant("revoke-enable");
        var failure = competeWithHeldRevocation(() -> daily.revokeGrant(project, g.id()), () -> enable(g));
        assertThat(failure).isInstanceOf(AgentDailyStateInvalidException.class);
        assertThat(daily.settings(project).enabled()).isFalse(); assertThat(daily.settings(project).revision()).isZero();
        assertThat(daily.getGrant(project, g.id()).state()).isEqualTo(DailyGrantState.REVOKED); noSpending();
    }
    Throwable competeWithHeldRevocation(Runnable revoke, Callable<?> competing) throws Exception {
        var executor = Executors.newFixedThreadPool(2); var held = new CountDownLatch(1); var release = new CountDownLatch(1); var pid = new AtomicInteger();
        var revocation = executor.submit(() -> { identity(OWNER); try {
            return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                pid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single()); revoke.run(); held.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                return true;
            });
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } });
        Future<Throwable> pending = null;
        try {
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            pending = executor.submit(() -> { identity(OWNER); try { return catchThrowable(competing::call); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } });
            boolean waiting = false; long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < until) {
                if (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE :pid=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock'").param("pid", pid.get()).query(Long.class).single() > 0) { waiting = true; break; }
                Thread.onSpinWait();
            }
            assertThat(waiting).as("control must wait for the uncommitted revocation's settings lock").isTrue(); assertThat(pending.isDone()).isFalse();
            release.countDown(); assertThat(revocation.get(10, TimeUnit.SECONDS)).isTrue(); return pending.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); revocation.cancel(true); if (pending != null) pending.cancel(true); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    <T> List<T> race(Callable<T> action) throws Exception {
        var executor = Executors.newFixedThreadPool(2); var barrier = new CyclicBarrier(2);
        Callable<T> task = () -> { identity(OWNER); try { barrier.await(5, TimeUnit.SECONDS); return action.call(); } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); } };
        try { var a = executor.submit(task); var b = executor.submit(task); return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)); }
        finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
}
