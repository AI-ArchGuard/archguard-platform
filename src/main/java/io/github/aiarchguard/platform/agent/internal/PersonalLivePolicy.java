package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentRuntimeAvailability;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 个人真实运行的本地准入策略，绑定唯一项目及批次并检查 profile、开关、HTTP 安全属性和数据库有限等待配置；
 * 可用状态不探测供应商。
 */
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

    /**
     * 同时要求个人 OIDC profile、独立 live 开关、凭据入口和安全 JVM/有限数据库等待配置；任何缺项均失败关闭。
     */
    void requireConfigured() {
        new PersonalRuntimeSafety(environment, dataSource).requireConfigured();
        if (!enabled("archguard.agent.live.enabled")) throw unavailable();
        id("project-id"); id("batch-id"); reference("network-check-ref"); reference("secret-check-ref");
    }

    /** 在原有界事务中核对唯一配置项目/批次及当前确认的检查引用；不能自动选择最新批准或用新批次恢复次数。 */
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
    private UUID id(String name) {
        try { return UUID.fromString(environment.getProperty("archguard.agent.live." + name, "")); }
        catch (RuntimeException invalid) { throw unavailable(); }
    }
    private String reference(String name) {
        String value = environment.getProperty("archguard.agent.live." + name, "");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || value.startsWith("sk-")) throw unavailable();
        return value;
    }
    private static AgentUnavailableException unavailable() { return new AgentUnavailableException(); }
}
