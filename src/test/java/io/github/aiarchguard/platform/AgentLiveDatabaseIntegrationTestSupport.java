package io.github.aiarchguard.platform;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Isolate fixed-clock live fixtures from recovery schedulers in other cached application contexts. */
abstract class AgentLiveDatabaseIntegrationTestSupport {
    private static final String DATABASE_URL = createDatabase();

    @DynamicPropertySource
    static void liveDatabaseProperties(DynamicPropertyRegistry registry) {
        var postgres = PostgresIntegrationTestSupport.POSTGRES;
        registry.add("spring.datasource.url", () -> DATABASE_URL);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("archguard.runner.enabled", () -> false);
    }

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
