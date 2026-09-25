package io.github.aiarchguard.platform;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

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
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("archguard.runner.enabled", () -> false);
    }
}
