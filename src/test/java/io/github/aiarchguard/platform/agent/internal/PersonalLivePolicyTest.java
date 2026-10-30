package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import io.github.aiarchguard.platform.agent.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 验证部署配置准入只匹配指定 Project/批次及有限数据库参数。使用未启动连接池与模拟环境，不探测账户、Key 或数据库连通性。
 */
class PersonalLivePolicyTest {
    private final UUID project = UUID.randomUUID();
    private MockEnvironment environment() {
        var env = new MockEnvironment().withProperty("archguard.agent.enabled", "true")
            .withProperty("archguard.agent.live.enabled", "true").withProperty("archguard.agent.credentials.enabled", "true")
            .withProperty("archguard.agent.live.project-id", project.toString())
            .withProperty("archguard.agent.live.batch-id", UUID.randomUUID().toString())
            .withProperty("archguard.agent.live.network-check-ref", "synthetic-network-check")
            .withProperty("archguard.agent.live.secret-check-ref", "synthetic-secret-check");
        env.setActiveProfiles("local-compose", "oidc");
        return env;
    }
    private HikariDataSource pool() {
        var pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:postgresql://db:5432/archguard");
        pool.setConnectionTimeout(1000); pool.setValidationTimeout(1000);
        pool.addDataSourceProperty("connectTimeout", "2"); pool.addDataSourceProperty("socketTimeout", "5");
        pool.addDataSourceProperty("cancelSignalTimeout", "1");
        return pool;
    }
    // 本地可用性是配置匹配判断，不启动连接池或查询供应商账户。
    @Test void configurationIsAnExactProjectAndBatchGateNotAnAccountProbe() {
        try (var pool = pool()) {
            var policy = new PersonalLivePolicy(environment(), pool, null, null);
            assertThat(policy.available(project)).isTrue();
            assertThat(policy.available(UUID.randomUUID())).isFalse();
            assertThat(policy.available(null)).isFalse();
            assertThat(pool.isRunning()).isFalse();
        }
    }
    // 合成适配器与 live 运行时不能同时启用。
    @Test void syntheticAdapterCannotBeCombinedWithTheLiveRuntime() {
        try (var pool = pool()) {
            var env = environment().withProperty("archguard.agent.synthetic-enabled", "true");
            assertThat(new PersonalLivePolicy(env, pool, null, null).available(project)).isFalse();
        }
    }
    // 缺失开关或有限数据库参数时返回不可用，不能借检查触发连接。
    @Test void defaultClosedAndMissingFiniteDatabaseConfigurationDoNotThrowOrStartAPool() {
        try (var pool = pool()) {
            assertThat(new PersonalLivePolicy(new MockEnvironment(), pool, null, null).available(project)).isFalse();
            var env = environment();
            pool.setConnectionTimeout(30000);
            assertThat(new PersonalLivePolicy(env, pool, null, null).available(project)).isFalse();
            pool.setConnectionTimeout(1000); pool.addDataSourceProperty("socketTimeout", "0");
            assertThat(new PersonalLivePolicy(env, pool, null, null).available(project)).isFalse();
            pool.addDataSourceProperty("socketTimeout", "5"); pool.setJdbcUrl("jdbc:postgresql://db:5432/archguard?socketTimeout=0");
            assertThat(new PersonalLivePolicy(env, pool, null, null).available(project)).isFalse();
            assertThat(pool.isRunning()).isFalse();
        }
    }

    // 共用技术防护不能删除原合成批次、模板、价格上限或当前确认的检查引用约束。
    @Test void sharedSafetyPreservesOriginalBatchOnlyGuards() {
        try (var pool = pool()) {
            var env = environment(); UUID batchId = UUID.fromString(env.getProperty("archguard.agent.live.batch-id"));
            UUID template = UUID.randomUUID(); UUID approvalId = UUID.randomUUID();
            var now = Instant.parse("2026-10-29T00:00:00Z");
            var manifest = new LiveManifest("0.1.0", project, List.of(new LiveManifest.Template(template, null, "a".repeat(64), "b".repeat(64))));
            var selected = new AtomicReference<LiveAccountingStore.Batch>();
            var approved = new AtomicReference<AgentEnablementStore.Approval>();
            var batches = (LiveAccountingStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{LiveAccountingStore.class},
                (proxy, method, args) -> { if (method.getName().equals("batch")) return Optional.ofNullable(selected.get());
                    throw new AssertionError("unexpected store operation"); });
            var approvals = (AgentEnablementStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{AgentEnablementStore.class},
                (proxy, method, args) -> { if (method.getName().equals("find")) return Optional.ofNullable(approved.get());
                    throw new AssertionError("unexpected store operation"); });
            var policy = new PersonalLivePolicy(env, pool, batches, approvals);
            var good = new LiveAccountingStore.Batch(new LiveBatchView(batchId, project, approvalId, project, now,
                "a".repeat(64), now.plusSeconds(60), 2, 632746, false), project, project, manifest, null, LiveCostPolicy.CONTEXT_VERSION);
            selected.set(good);
            var ack = new PersonalEnablementInput("0.4.0", null, null, null, null, null, null, true, "deepseek-flash",
                "DeepSeek-V4.1-Flash-1017", List.of("deepseek-flash"), LiveCostPolicy.CONTEXT_VERSION, null,
                "synthetic-secret-check", "synthetic-network-check", null, null, project);
            approved.set(new AgentEnablementStore.Approval(approvalId, project, project, project, now, ack, null));
            assertThatCode(() -> policy.requireBatch(project, batchId, template)).doesNotThrowAnyException();
            assertThatThrownBy(() -> policy.requireBatch(UUID.randomUUID(), batchId, template)).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(() -> policy.requireBatch(project, UUID.randomUUID(), template)).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(() -> policy.requireBatch(project, batchId, null)).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(() -> policy.requireBatch(project, batchId, UUID.randomUUID())).isInstanceOf(AgentUnavailableException.class);
            for (String check : List.of("network-check-ref", "secret-check-ref")) {
                String old = env.getProperty("archguard.agent.live." + check);
                env.setProperty("archguard.agent.live." + check, "changed");
                assertThatThrownBy(() -> policy.requireBatch(project, batchId, template)).isInstanceOf(AgentUnavailableException.class);
                env.setProperty("archguard.agent.live." + check, old);
            }
            selected.set(new LiveAccountingStore.Batch(good.view(), project, project, manifest, null, LiveCostPolicy.VERSION));
            assertThatThrownBy(() -> policy.requireBatch(project, batchId, template)).isInstanceOf(AgentUnavailableException.class);
            for (int count : List.of(1, 3)) {
                selected.set(new LiveAccountingStore.Batch(new LiveBatchView(batchId, project, approvalId, project, now,
                    "a".repeat(64), now.plusSeconds(60), count, 632746, false), project, project, manifest, null, LiveCostPolicy.CONTEXT_VERSION));
                assertThatThrownBy(() -> policy.requireBatch(project, batchId, template)).isInstanceOf(AgentUnavailableException.class);
            }
            selected.set(new LiveAccountingStore.Batch(new LiveBatchView(batchId, project, approvalId, project, now,
                "a".repeat(64), now.plusSeconds(60), 2, 700001, false), project, project, manifest, null, LiveCostPolicy.CONTEXT_VERSION));
            assertThatThrownBy(() -> policy.requireBatch(project, batchId, template)).isInstanceOf(AgentUnavailableException.class);
            selected.set(good); approved.set(null);
            assertThatThrownBy(() -> policy.requireBatch(project, batchId, template)).isInstanceOf(AgentUnavailableException.class);
            assertThat(pool.isRunning()).isFalse();
        }
    }
}
