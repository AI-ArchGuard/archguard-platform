package io.github.aiarchguard.platform;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 为固定时钟 live 测试建立独立数据库，隔离其他缓存 Spring 上下文中的恢复调度器。数据库仍复用测试容器，避免系统时钟恢复逻辑误处理合成历史。
 *
 * Isolate fixed-clock live fixtures from recovery schedulers in other cached application contexts.
 */
abstract class AgentLiveDatabaseIntegrationTestSupport {
    private static final String DATABASE_URL = createDatabase();

    @DynamicPropertySource
    // 这些 live 场景共用隔离库，保持与普通集成测试上下文的时钟边界。
    static void liveDatabaseProperties(DynamicPropertyRegistry registry) {
        var postgres = PostgresIntegrationTestSupport.POSTGRES;
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("archguard.runner.enabled", () -> false);
    }

    // 创建随机命名测试数据库并设置有限 SQL 等待，失败直接终止夹具初始化。
    private static String createDatabase() {
        var postgres = PostgresIntegrationTestSupport.POSTGRES;
        String database = "archguard_live_deadline_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute("CREATE DATABASE " + database);
            return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        } catch (SQLException unavailable) { throw new IllegalStateException("Synthetic live test database unavailable", unavailable); }
    }
}
