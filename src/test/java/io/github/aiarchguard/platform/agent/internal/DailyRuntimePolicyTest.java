package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import io.github.aiarchguard.platform.agentcredential.CredentialStatus;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 纯本地策略测试：未启动连接池；没有应用上下文、Key 端口或模型/HTTP 适配器。 */
class DailyRuntimePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID DEPLOYMENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID VERSION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String P = "archguard.agent.daily.";
    private static final String[] KINDS = {"TERMS", "PRIVACY", "CACHE", "MODEL_PRICE", "PROTOCOL"};
    private static final String[] URLS = {
        "https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html",
        "https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html",
        "https://api-docs.deepseek.com/guides/kv_cache/", "https://api-docs.deepseek.com/quick_start/pricing/",
        "https://api-docs.deepseek.com/guides/responses_api/"};

    private MockEnvironment environment() {
        var env = new MockEnvironment().withProperty("archguard.agent.enabled", "true")
            .withProperty(P + "enabled", "true").withProperty("archguard.agent.credentials.enabled", "true")
            .withProperty("archguard.agent.credentials.owner-id", OWNER.toString())
            .withProperty("archguard.agent.credentials.deployment-id", DEPLOYMENT.toString())
            .withProperty(P + "runtime-revision", "a".repeat(64))
            .withProperty(P + "secret-check-ref", "synthetic-secret").withProperty(P + "network-check-ref", "synthetic-network")
            .withProperty(P + "evidence.price-catalog-expires-at", NOW.plusSeconds(3600).toString());
        env.setActiveProfiles("local-compose", "oidc");
        for (int i = 0; i < 5; i++) {
            env.setProperty(source(i, "kind"), KINDS[i]); env.setProperty(source(i, "url"), URLS[i]);
            env.setProperty(source(i, "version"), "synthetic-reviewed-v1");
            env.setProperty(source(i, "checked-at"), NOW.minusSeconds(60).toString());
        }
        return env;
    }
    private static String source(int i, String field) { return P + "evidence.sources[" + i + "]." + field; }
    private HikariDataSource pool() {
        var pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:postgresql://db:5432/archguard");
        pool.setConnectionTimeout(1000); pool.setValidationTimeout(1000);
        pool.addDataSourceProperty("connectTimeout", "2"); pool.addDataSourceProperty("socketTimeout", "5");
        pool.addDataSourceProperty("cancelSignalTimeout", "1"); return pool;
    }
    private DailyRuntimePolicy policy(MockEnvironment env, HikariDataSource pool) {
        return new DailyRuntimePolicy(env, pool, () -> new CredentialStatus(true, VERSION, NOW), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private void rejected(MockEnvironment env, HikariDataSource pool) {
        assertThatThrownBy(() -> policy(env, pool).binding()).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        assertThat(pool.isRunning()).isFalse();
    }

    @Test void constructorAndDefaultsDoNotReadMetadataOrOpenResources() {
        try (var pool = pool()) {
            var reads = new AtomicInteger();
            var policy = new DailyRuntimePolicy(new MockEnvironment(), pool,
                () -> { reads.incrementAndGet(); throw new AssertionError("unexpected metadata read"); }, Clock.systemUTC());
            assertThat(reads.get()).isZero();
            assertThatThrownBy(policy::requireConfigured).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(policy::binding).isInstanceOf(AgentUnavailableException.class);
            assertThat(reads.get()).isZero(); assertThat(pool.isRunning()).isFalse();
        }
    }
    @Test void eachCallSwitchIsIndependentOfReadableFactualBindingAndLegacyBatchSwitch() {
        try (var pool = pool()) {
            assertThatCode(() -> policy(environment(), pool).binding()).doesNotThrowAnyException();
            var baseline = policy(environment(), pool).binding();
            for (String key : List.of("archguard.agent.enabled", P + "enabled", "archguard.agent.credentials.enabled")) {
                var env = environment().withProperty(key, "false");
                assertThat(policy(env, pool).binding()).isEqualTo(baseline);
                assertThatThrownBy(() -> policy(env, pool).requireConfigured()).isInstanceOf(AgentUnavailableException.class);
            }
            for (String live : List.of("true", "false")) {
                var p = policy(environment().withProperty("archguard.agent.live.enabled", live), pool);
                assertThatCode(p::requireConfigured).doesNotThrowAnyException();
                assertThat(p.binding()).isEqualTo(baseline);
            }
            assertThat(pool.isRunning()).isFalse();
        }
    }
    @Test void technicalEligibilityDoesNotPerformAnUnboundedMetadataReadBeforeBoundedRevalidation() {
        try (var pool = pool()) {
            var reads = new AtomicInteger();
            var p = new DailyRuntimePolicy(environment(), pool, () -> {
                reads.incrementAndGet(); return new CredentialStatus(true, VERSION, NOW);
            }, Clock.fixed(NOW, ZoneOffset.UTC));
            p.requireConfigured();
            assertThat(reads.get()).isZero();
        }
    }
    @Test void explicitIdentityRevisionChecksAndOperatorEvidenceAreRequired() {
        try (var pool = pool()) {
            for (String key : List.of("archguard.agent.credentials.owner-id", "archguard.agent.credentials.deployment-id",
                    P + "runtime-revision", P + "secret-check-ref", P + "network-check-ref", P + "evidence.price-catalog-expires-at")) {
                for (String bad : List.of("", "bad value", "sk-forbidden")) rejected(environment().withProperty(key, bad), pool);
            }
            rejected(environment().withProperty(P + "runtime-revision", "A".repeat(64)), pool);
            rejected(environment().withProperty(P + "network-check-ref", "x".repeat(129)), pool);
            var b = policy(environment(), pool).binding();
            assertThat(b.ownerId()).isEqualTo(OWNER); assertThat(b.deploymentId()).isEqualTo(DEPLOYMENT);
            assertThat(b.credentialVersion()).isEqualTo(VERSION);
            assertThat(b.modelAlias()).isEqualTo("deepseek-flash");
            assertThat(b.mappingSnapshot()).isEqualTo("DeepSeek-V4.1-Flash-1017");
            assertThat(b.modelProtocolVersion()).isEqualTo("responses-v1-restricted");
            assertThat(b.explanationPromptVersion()).isEqualTo("finding-explanation-deepseek-0.1.0");
            assertThat(b.summaryPromptVersion()).isEqualTo("pr-summary-deepseek-0.1.0");
            assertThat(b.allowedResponseModels()).containsExactly("deepseek-flash");
            assertThat(b.outputSchemaVersion()).isEqualTo("0.1.0");
            assertThat(b.priceCatalogVersion()).isEqualTo("deepseek-flash-context-peak-usd-2026-10-15");
            assertThat(b.dataPolicyVersion()).isEqualTo("personal-selected-nonsensitive-0.1.0");
            assertThat(b.evidence().sources()).allSatisfy(s -> assertThat(s.checkedAt()).isEqualTo(NOW.minusSeconds(60)));
        }
    }
    @Test void everySourceFieldIsValidatedAndFiveUniqueOfficialSourcesAreRequired() {
        try (var pool = pool()) {
            for (int i = 0; i < 5; i++) {
                for (String field : List.of("kind", "url", "version", "checked-at")) rejected(environment().withProperty(source(i, field), ""), pool);
                rejected(environment().withProperty(source(i, "kind"), "UNKNOWN"), pool);
                rejected(environment().withProperty(source(i, "url"), "https://example.invalid/"), pool);
                for (String ref : List.of("sk-forbidden", "bad ref", "x".repeat(129))) rejected(environment().withProperty(source(i, "version"), ref), pool);
                for (String time : List.of("invalid", NOW.plusSeconds(1).toString(), NOW.minusSeconds(604801).toString())) {
                    rejected(environment().withProperty(source(i, "checked-at"), time), pool);
                }
            }
            rejected(environment().withProperty(source(4, "kind"), "TERMS").withProperty(source(4, "url"), URLS[0]), pool);
            rejected(environment().withProperty(source(5, "kind"), "TERMS"), pool);
            rejected(environment().withProperty(P + "evidence.price-catalog-expires-at", NOW.toString()), pool);
            for (Instant boundary : List.of(NOW, NOW.minusSeconds(604800))) {
                assertThat(policy(environment().withProperty(source(0, "checked-at"), boundary.toString()), pool).binding()).isNotNull();
            }
        }
    }
    @Test void credentialMetadataMustBeAvailableAndBoundedPathNeverFallsBackOrResetsDeadline() {
        try (var pool = pool()) {
            for (CredentialStatus status : Arrays.asList(null, CredentialStatus.absent(), new CredentialStatus(true, null, NOW))) {
                var p = new DailyRuntimePolicy(environment(), pool, () -> status, Clock.fixed(NOW, ZoneOffset.UTC));
                assertThatThrownBy(p::binding).isInstanceOf(AgentUnavailableException.class).hasNoCause();
            }
            LongSupplier remaining = () -> 1000;
            CredentialVersionSource bounded = new CredentialVersionSource() {
                public CredentialStatus current() { throw new AssertionError("unbounded fallback"); }
                public CredentialStatus current(LongSupplier supplied) {
                    assertThat(supplied).isSameAs(remaining); return new CredentialStatus(true, VERSION, NOW);
                }
            };
            var p = new DailyRuntimePolicy(environment(), pool, bounded, Clock.fixed(NOW, ZoneOffset.UTC));
            assertThatCode(() -> p.binding(remaining)).doesNotThrowAnyException();
            assertThat(p.binding(remaining).credentialVersion()).isEqualTo(VERSION);
            assertThatThrownBy(() -> p.binding(() -> 0)).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(() -> policy(environment(), pool).binding(remaining)).isInstanceOf(AgentUnavailableException.class);
            var budget = new java.util.concurrent.atomic.AtomicLong(1000);
            var expiresDuringRead = new CredentialVersionSource() {
                public CredentialStatus current() { throw new AssertionError("unbounded fallback"); }
                public CredentialStatus current(LongSupplier supplied) {
                    budget.set(0); return new CredentialStatus(true, VERSION, NOW);
                }
            };
            var expiring = new DailyRuntimePolicy(environment(), pool, expiresDuringRead, Clock.fixed(NOW, ZoneOffset.UTC));
            assertThatThrownBy(() -> expiring.binding(budget::get)).isInstanceOf(AgentUnavailableException.class);
            var unavailable = new DailyRuntimePolicy(environment(), pool, () -> { throw new IllegalStateException("sensitive"); }, Clock.systemUTC());
            assertThatThrownBy(unavailable::binding).isInstanceOf(AgentUnavailableException.class).hasNoCause();
        }
    }
    // 元数据锁等待结束时必须按新时间校验同一份证据，不能只依赖仍为正的执行预算。
    @Test void ordinaryMetadataWaitCannotReturnAnExpiredPriceCatalog() {
        assertThatThrownBy(() -> bindingAfterMetadataWait(false, NOW.plusSeconds(3600), NOW.plusSeconds(3600), NOW.minusSeconds(60)))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
    }
    @Test void boundedMetadataWaitCannotReturnAnExpiredPriceCatalog() {
        assertThatThrownBy(() -> bindingAfterMetadataWait(true, NOW.plusSeconds(3601), NOW.plusSeconds(3600), NOW.minusSeconds(60)))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
    }
    @Test void ordinaryMetadataWaitCannotReturnASourceOlderThanSevenDays() {
        assertThatThrownBy(() -> bindingAfterMetadataWait(false, NOW.plusSeconds(2), NOW.plusSeconds(3600), NOW.minusSeconds(604799)))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
    }
    @Test void boundedMetadataWaitCannotReturnASourceOlderThanSevenDays() {
        assertThatThrownBy(() -> bindingAfterMetadataWait(true, NOW.plusSeconds(2), NOW.plusSeconds(3600), NOW.minusSeconds(604799)))
            .isInstanceOf(AgentUnavailableException.class).hasNoCause();
    }
    @Test void postMetadataFreshnessKeepsInclusiveSourceBoundaryAndStrictPriceBoundary() {
        for (boolean bounded : List.of(false, true)) {
            var b = bindingAfterMetadataWait(bounded, NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.minusSeconds(604799));
            assertThat(b.evidence().sources().getFirst().checkedAt()).isEqualTo(NOW.minusSeconds(604799));
            assertThat(b.evidence().priceCatalogExpiresAt()).isEqualTo(NOW.plusSeconds(2));
            assertThat(b.ownerId()).isEqualTo(OWNER); assertThat(b.runtimeRevision()).isEqualTo("a".repeat(64));
            assertThat(b.credentialVersion()).isEqualTo(VERSION);
            assertThatThrownBy(() -> bindingAfterMetadataWait(bounded, NOW.plusSeconds(2), NOW.plusSeconds(2), NOW.minusSeconds(60)))
                .isInstanceOf(AgentUnavailableException.class).hasNoCause();
        }
    }
    private DailyBindingSnapshot bindingAfterMetadataWait(boolean bounded, Instant afterWait, Instant priceExpiry, Instant checkedAt) {
        try (var pool = pool()) {
            var env = environment().withProperty(P + "evidence.price-catalog-expires-at", priceExpiry.toString())
                .withProperty(source(0, "checked-at"), checkedAt.toString());
            var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
            Clock advancing = new Clock() {
                public ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                public Instant instant() { return time.get(); }
            };
            var reads = new AtomicInteger();
            LongSupplier remaining = () -> 1000;
            CredentialVersionSource metadata = new CredentialVersionSource() {
                public CredentialStatus current() {
                    assertThat(bounded).isFalse(); return afterWait();
                }
                public CredentialStatus current(LongSupplier supplied) {
                    assertThat(bounded).isTrue(); assertThat(supplied).isSameAs(remaining); return afterWait();
                }
                private CredentialStatus afterWait() {
                    reads.incrementAndGet(); time.set(afterWait);
                    // 等待期间配置变化不能替换本次捕获的配置和证据，或把过期事实刷新成有效事实。
                    env.setProperty("archguard.agent.credentials.owner-id", DEPLOYMENT.toString());
                    env.setProperty(P + "runtime-revision", "b".repeat(64));
                    env.setProperty(P + "evidence.price-catalog-expires-at", afterWait.plusSeconds(3600).toString());
                    env.setProperty(source(0, "checked-at"), afterWait.toString());
                    return new CredentialStatus(true, VERSION, NOW);
                }
            };
            var p = new DailyRuntimePolicy(env, pool, metadata, advancing);
            try { return bounded ? p.binding(remaining) : p.binding(); }
            finally { assertThat(reads.get()).isEqualTo(1); assertThat(pool.isRunning()).isFalse(); }
        }
    }
    @Test void digestUsesCapturedSnapshotIsOrderIndependentAndSensitiveToEveryBindingAndEvidenceField() throws Exception {
        try (var pool = pool()) {
            var reads = new AtomicInteger();
            var p = new DailyRuntimePolicy(environment(), pool, () -> new CredentialStatus(true,
                reads.incrementAndGet() == 1 ? VERSION : OWNER, NOW), Clock.fixed(NOW, ZoneOffset.UTC));
            var b = p.binding(); String digest = DailyRuntimePolicy.digest(b);
            assertThat(digest).matches("[a-f0-9]{64}"); assertThat(DailyRuntimePolicy.digest(b)).isEqualTo(digest);
            assertThat(reads.get()).isEqualTo(1);
            var later = new DailyRuntimePolicy(environment(), pool, () -> new CredentialStatus(true, VERSION, NOW),
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
            assertThat(DailyRuntimePolicy.digest(later.binding())).isEqualTo(digest);
            var reversed = new ArrayList<>(b.evidence().sources()); Collections.reverse(reversed);
            assertThat(DailyRuntimePolicy.digest(replace(b, "evidence", new DailyEvidence(reversed, b.evidence().priceCatalogExpiresAt())))).isEqualTo(digest);
            for (var field : DailyBindingSnapshot.class.getRecordComponents()) {
                Object old = field.getAccessor().invoke(b);
                Object next = old instanceof UUID ? UUID.randomUUID() : old instanceof String s ? s + "x"
                    : old instanceof List<?> ? List.of("changed") : new DailyEvidence(b.evidence().sources(), NOW.plusSeconds(7200));
                assertThat(DailyRuntimePolicy.digest(replace(b, field.getName(), next))).as(field.getName()).isNotEqualTo(digest);
            }
            var first = b.evidence().sources().getFirst();
            for (String field : List.of("kind", "url", "version", "checkedAt")) {
                var changed = new DailySource(field.equals("kind") ? "OTHER" : first.kind(), field.equals("url") ? first.url() + "x" : first.url(),
                    field.equals("version") ? "changed" : first.version(), field.equals("checkedAt") ? NOW : first.checkedAt());
                var sources = new ArrayList<>(b.evidence().sources()); sources.set(0, changed);
                assertThat(DailyRuntimePolicy.digest(replace(b, "evidence", new DailyEvidence(sources, b.evidence().priceCatalogExpiresAt())))).as(field).isNotEqualTo(digest);
            }
            assertThat(DailyRuntimePolicy.digest(p.binding())).isNotEqualTo(digest);
            for (String key : List.of("archguard.agent.credentials.owner-id", "archguard.agent.credentials.deployment-id")) {
                assertThat(DailyRuntimePolicy.digest(policy(environment().withProperty(key, VERSION.toString()), pool).binding())).isNotEqualTo(digest);
            }
        }
    }
    @Test void technicalSafetyRejectsUnsafeProfilesJvmAndFiniteDatabaseSettingsWithoutConnecting() {
        try (var pool = pool()) {
            var noPool = new DailyRuntimePolicy(environment(), null, () -> new CredentialStatus(true, VERSION, NOW), Clock.systemUTC());
            assertThatThrownBy(noPool::requireConfigured).isInstanceOf(AgentUnavailableException.class);
            for (String[] profiles : List.of(new String[]{}, new String[]{"local-compose"}, new String[]{"oidc"})) {
                var env = environment(); env.setActiveProfiles(profiles);
                assertThatThrownBy(() -> policy(env, pool).requireConfigured()).isInstanceOf(AgentUnavailableException.class);
            }
            assertThatThrownBy(() -> policy(environment().withProperty("archguard.agent.synthetic-enabled", "true"), pool).requireConfigured())
                .isInstanceOf(AgentUnavailableException.class);
            for (String key : List.of("jdk.httpclient.disableRetryConnect", "jdk.httpclient.enableAllMethodRetry",
                    "jdk.internal.httpclient.disableHostnameVerification", "jdk.httpclient.HttpClient.log")) {
                String old = System.getProperty(key);
                try {
                    System.setProperty(key, key.equals("jdk.httpclient.disableRetryConnect") ? "false" : "true");
                    assertThatThrownBy(() -> policy(environment(), pool).requireConfigured()).isInstanceOf(AgentUnavailableException.class);
                } finally { if (old == null) System.clearProperty(key); else System.setProperty(key, old); }
            }
            for (String key : List.of("connectTimeout", "socketTimeout", "cancelSignalTimeout")) {
                String old = pool.getDataSourceProperties().getProperty(key);
                for (String bad : List.of("0", "-1", "999", "bad")) {
                    pool.addDataSourceProperty(key, bad);
                    assertThatThrownBy(() -> policy(environment(), pool).requireConfigured()).isInstanceOf(AgentUnavailableException.class);
                }
                pool.addDataSourceProperty(key, old);
            }
            pool.setConnectionTimeout(30000);
            assertThatThrownBy(() -> policy(environment(), pool).requireConfigured()).isInstanceOf(AgentUnavailableException.class);
            pool.setConnectionTimeout(1000); pool.setValidationTimeout(30000);
            assertThatThrownBy(() -> policy(environment(), pool).requireConfigured()).isInstanceOf(AgentUnavailableException.class);
            pool.setValidationTimeout(1000);
            for (String url : List.of("jdbc:h2:mem:test", "jdbc:postgresql://db/db?socketTimeout=0", "jdbc:postgresql://db/db?loggerLevel=OFF&socketTimeout=0")) {
                pool.setJdbcUrl(url);
                assertThatThrownBy(() -> policy(environment(), pool).requireConfigured()).isInstanceOf(AgentUnavailableException.class);
            }
            assertThat(pool.isRunning()).isFalse();
        }
    }
    private DailyBindingSnapshot replace(DailyBindingSnapshot original, String field, Object value) throws Exception {
        var components = DailyBindingSnapshot.class.getRecordComponents(); var values = new Object[components.length];
        for (int i = 0; i < components.length; i++) values[i] = components[i].getName().equals(field) ? value : components[i].getAccessor().invoke(original);
        return DailyBindingSnapshot.class.getDeclaredConstructor(Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class[]::new)).newInstance(values);
    }
}
