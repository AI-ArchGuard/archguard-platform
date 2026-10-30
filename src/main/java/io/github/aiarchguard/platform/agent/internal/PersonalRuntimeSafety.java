package io.github.aiarchguard.platform.agent.internal;

import com.zaxxer.hikari.HikariDataSource;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** 共用本地技术防护；只读连接池配置，不取得连接，不代表业务授权或发送许可。 */
final class PersonalRuntimeSafety {
    private final Environment environment;
    private final DataSource dataSource;
    PersonalRuntimeSafety(Environment environment, DataSource dataSource) {
        this.environment = environment; this.dataSource = dataSource;
    }
    void requireConfigured() {
        if (!environment.acceptsProfiles(Profiles.of("local-compose & oidc"))
                || !enabled("archguard.agent.enabled")
                || !enabled("archguard.agent.credentials.enabled") || enabled("archguard.agent.synthetic-enabled")) throw unavailable();
        if (!"true".equals(System.getProperty("jdk.httpclient.disableRetryConnect"))
                || !safeFalse("jdk.httpclient.enableAllMethodRetry") || !safeFalse("jdk.internal.httpclient.disableHostnameVerification")
                || !System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()) throw unavailable();
        if (!(dataSource instanceof HikariDataSource pool) || pool.getConnectionTimeout() < 250
                || pool.getConnectionTimeout() > 1000 || pool.getValidationTimeout() < 250
                || pool.getValidationTimeout() > 1000 || pool.getJdbcUrl() == null) throw unavailable();
        // URL properties override driver properties. Only the non-network test logger option is allowed here.
        String url = pool.getJdbcUrl();
        if (!url.startsWith("jdbc:postgresql://")) throw unavailable();
        int query = url.indexOf('?');
        if (query >= 0 && !"loggerLevel=OFF".equals(url.substring(query + 1))) throw unavailable();
        finite(pool, "connectTimeout", 2); finite(pool, "socketTimeout", 5); finite(pool, "cancelSignalTimeout", 1);
    }
    private boolean enabled(String key) { return environment.getProperty(key, Boolean.class, false); }
    private static boolean safeFalse(String key) {
        String value = System.getProperty(key);
        return value == null || "false".equals(value);
    }
    private static void finite(HikariDataSource pool, String key, int maximum) {
        try {
            int value = Integer.parseInt(pool.getDataSourceProperties().getProperty(key, "0"));
            if (value <= 0 || value > maximum) throw unavailable();
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    private static AgentUnavailableException unavailable() { return new AgentUnavailableException(); }
}
