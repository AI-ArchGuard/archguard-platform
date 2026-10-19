package io.github.aiarchguard.platform.agent.internal;

import com.zaxxer.hikari.HikariDataSource;
import io.github.aiarchguard.platform.agent.AgentRuntimeAvailability;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** One explicitly bound synthetic acceptance batch; never wildcard authorization. */
@Component
final class PersonalLivePolicy implements AgentRuntimeAvailability {
    private final Environment environment;
    private final DataSource dataSource;
    private final LiveAccountingStore batches;
    private final AgentEnablementStore enablements;

    PersonalLivePolicy(Environment environment, DataSource dataSource, LiveAccountingStore batches,
            AgentEnablementStore enablements) {
        this.environment = environment; this.dataSource = dataSource; this.batches = batches; this.enablements = enablements;
    }

    @Override public boolean available(UUID project) {
        try { requireConfigured(); return project != null && project.equals(id("project-id")); }
        catch (RuntimeException unavailable) { return false; }
    }

    void requireConfigured() {
        if (!environment.acceptsProfiles(Profiles.of("local-compose & oidc"))
                || !enabled("archguard.agent.enabled") || !enabled("archguard.agent.live.enabled")
                || !enabled("archguard.agent.credentials.enabled") || enabled("archguard.agent.synthetic-enabled")) throw unavailable();
        if (!"true".equals(System.getProperty("jdk.httpclient.disableRetryConnect"))
                || !safeFalse("jdk.httpclient.enableAllMethodRetry") || !safeFalse("jdk.internal.httpclient.disableHostnameVerification")
                || !System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()) throw unavailable();
        id("project-id"); id("batch-id"); reference("network-check-ref"); reference("secret-check-ref");
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

    /** Must run inside the caller's original bounded database transaction. */
    void requireBatch(UUID project, UUID batchId, UUID template) {
        requireConfigured();
        if (!id("project-id").equals(project) || !id("batch-id").equals(batchId) || template == null) throw unavailable();
        var batch = batches.batch(project, batchId).orElseThrow(AgentUnavailableException::new);
        if (!LiveCostPolicy.CONTEXT_VERSION.equals(batch.costPolicyVersion()) || batch.view().maxRequests() != 2
                || batch.view().maxCostMicrousd() > LiveCostPolicy.BATCH_LIMIT
                || batch.manifest().templates().stream().noneMatch(t -> template.equals(t.templateRequestId()))) throw unavailable();
        var approval = enablements.find(project, batch.view().enablementId()).orElseThrow(AgentUnavailableException::new);
        if (!PersonalEnablementPolicy.currentMapping(approval.acknowledgement())
                || !reference("network-check-ref").equals(approval.acknowledgement().networkCheckRef())
                || !reference("secret-check-ref").equals(approval.acknowledgement().secretCheckRef())) throw unavailable();
    }

    private boolean enabled(String key) { return environment.getProperty(key, Boolean.class, false); }
    private static boolean safeFalse(String key) {
        String value = System.getProperty(key);
        return value == null || "false".equals(value);
    }
    private UUID id(String name) {
        try { return UUID.fromString(environment.getProperty("archguard.agent.live." + name, "")); }
        catch (RuntimeException invalid) { throw unavailable(); }
    }
    private String reference(String name) {
        String value = environment.getProperty("archguard.agent.live." + name, "");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || value.startsWith("sk-")) throw unavailable();
        return value;
    }
    private static void finite(HikariDataSource pool, String key, int maximum) {
        try {
            int value = Integer.parseInt(pool.getDataSourceProperties().getProperty(key, "0"));
            if (value <= 0 || value > maximum) throw unavailable();
        } catch (RuntimeException invalid) { throw unavailable(); }
    }
    private static AgentUnavailableException unavailable() { return new AgentUnavailableException(); }
}
