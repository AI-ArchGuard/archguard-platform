package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agentcredential.*;
import io.github.aiarchguard.platform.audit.*;
import io.github.aiarchguard.platform.audit.persistence.JdbcAuditRecorder;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** 独立且明确为合成的五来源夹具；只替换时间/凭据元数据/故障注入，保留真实 JDBC 审计与事务。 */
@TestConfiguration
public class DailyControlTestConfiguration {
    public static final Instant NOW = Instant.parse("2026-10-30T02:00:00Z");
    public static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    public static final UUID DEPLOYMENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    public static final UUID VERSION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    public static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
    public static final AtomicReference<CredentialStatus> METADATA = new AtomicReference<>();
    public static final AtomicReference<LongSupplier> ORIGINAL_BUDGET = new AtomicReference<>();
    public static final AtomicBoolean BOUNDED_ONLY = new AtomicBoolean();
    public static final AtomicBoolean FAIL_AUDIT = new AtomicBoolean();
    public static final AtomicInteger MODEL_CALLS = new AtomicInteger();
    public static final MockEnvironment ENV = environment();
    /** 仅测试桥接已有唯一摘要实现，不公开策略类，也不另造历史绑定散列算法。 */
    public static String bindingDigest(io.github.aiarchguard.platform.agent.DailyAgentOperations.DailyBindingSnapshot binding) {
        return DailyRuntimePolicy.digest(binding);
    }
    /** 原地恢复全部合成配置，保留已构造策略持有的 Environment 引用，避免用例间遗留变更。 */
    public static void resetFixture() {
        TIME.set(NOW); METADATA.set(new CredentialStatus(true, VERSION, NOW.minusSeconds(500)));
        FAIL_AUDIT.set(false); BOUNDED_ONLY.set(false); ORIGINAL_BUDGET.set(null); MODEL_CALLS.set(0);
        var baseline = environment();
        var current = (MapPropertySource) ENV.getPropertySources().get("mockProperties");
        var original = (MapPropertySource) baseline.getPropertySources().get("mockProperties");
        current.getSource().clear(); current.getSource().putAll(original.getSource());
        ENV.setActiveProfiles(baseline.getActiveProfiles());
    }
    private static MockEnvironment environment() {
        var e = new MockEnvironment().withProperty("archguard.agent.enabled", "true")
            .withProperty("archguard.agent.credentials.enabled", "true")
            .withProperty("archguard.agent.credentials.owner-id", OWNER.toString())
            .withProperty("archguard.agent.credentials.deployment-id", DEPLOYMENT.toString())
            .withProperty("archguard.agent.daily.enabled", "true")
            .withProperty("archguard.agent.daily.runtime-revision", "a".repeat(64))
            .withProperty("archguard.agent.daily.secret-check-ref", "synthetic-daily-secret")
            .withProperty("archguard.agent.daily.network-check-ref", "synthetic-daily-network")
            .withProperty("archguard.agent.daily.evidence.price-catalog-expires-at", "2026-10-31T02:00:00Z");
        e.setActiveProfiles("local-compose", "oidc");
        String[] kinds = {"TERMS", "PRIVACY", "CACHE", "MODEL_PRICE", "PROTOCOL"};
        String[] urls = {"https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html",
            "https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html",
            "https://api-docs.deepseek.com/guides/kv_cache/", "https://api-docs.deepseek.com/quick_start/pricing/",
            "https://api-docs.deepseek.com/guides/responses_api/"};
        for (int i = 0; i < kinds.length; i++) {
            String p = "archguard.agent.daily.evidence.sources[" + i + "].";
            e.setProperty(p + "kind", kinds[i]); e.setProperty(p + "url", urls[i]);
            e.setProperty(p + "version", "synthetic-daily-reviewed-1017-v1");
            e.setProperty(p + "checked-at", "2026-10-30T01:00:00Z");
        }
        return e;
    }
    @Bean DailyAgentControlService dailyControl(DataSource source, CredentialVersionSource versions, Clock clock) {
        return new DailyAgentControlService(new DailyRuntimePolicy(ENV, source, versions, clock));
    }
    @Bean @Primary Clock dailyClock() {
        return new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
            public Instant instant() { return TIME.get(); }
        };
    }
    @Bean @Primary CredentialVersionSource dailyMetadata() {
        return new CredentialVersionSource() {
            public CredentialStatus current() {
                if (BOUNDED_ONLY.get()) throw new AssertionError("Unbounded credential metadata read");
                return METADATA.get();
            }
            public CredentialStatus current(LongSupplier remaining) {
                ORIGINAL_BUDGET.set(remaining);
                if (remaining == null || remaining.getAsLong() <= 0) throw new AgentUnavailableException();
                return METADATA.get();
            }
        };
    }
    @Bean @Primary AuditRecorder dailyAudit(JdbcAuditRecorder delegate) {
        return event -> {
            if (event.action().startsWith("agent.daily.") && FAIL_AUDIT.getAndSet(false)) throw new AgentUnavailableException();
            delegate.record(event);
        };
    }
    @Bean @Primary AgentModelPort dailyNoDispatch() {
        return new AgentModelPort() {
            public boolean available() { return false; }
            public boolean syntheticOnly() { return true; }
            public ModelResponse explain(ModelInput input) {
                MODEL_CALLS.incrementAndGet(); throw new AssertionError("Daily controls must not dispatch");
            }
        };
    }
}
