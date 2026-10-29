package io.github.aiarchguard.platform;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 集成测试共享的 PostgreSQL 容器支撑，向 Spring 注入临时数据库连接并禁用 Runner。容器按 JVM 生命周期保留，以配合 Spring 测试上下文缓存；它不是生产数据库配置。
 */
abstract class PostgresIntegrationTestSupport {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine")
        .withDatabaseName("archguard")
        .withUsername("archguard_test")
        .withPassword("test-only-password");

    static {
        // Spring caches application contexts across test classes; keep their database alive for the JVM.
        POSTGRES.start();
    }

    @DynamicPropertySource
    // 仅覆盖测试连接及 Runner 开关，避免集成测试误启动外部扫描执行。
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("archguard.runner.enabled", () -> false);
    }
}
