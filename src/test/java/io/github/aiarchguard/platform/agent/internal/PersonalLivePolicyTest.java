package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
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
}
