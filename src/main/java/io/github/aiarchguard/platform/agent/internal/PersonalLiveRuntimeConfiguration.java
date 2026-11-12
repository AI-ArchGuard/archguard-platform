package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.common.TraceTaskScope;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.identity.AuthenticatedTaskScope;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 在同模块装配真实执行能力，串起身份/原始期限、精确批次、公开资源核验与基础设施惰性适配器；旧无界入口拒绝，启动装配不自动外发。
 */
@Configuration
class PersonalLiveRuntimeConfiguration {
    /**
     * 将默认拒绝的公共构造边界替换为同模块受控能力；每次调用检查配置/身份，资源核验位于有界只读事务而非 Key 回调中。
     */
    @Bean(destroyMethod = "close")
    PersonalLiveRuntimeResources personalLiveRuntimeResources(LiveAdapterBundle adapters) {
        return new PersonalLiveRuntimeResources(adapters);
    }

    @org.springframework.context.annotation.Primary
    @Bean(destroyMethod = "")
    PersonalLiveRuntime personalLiveRuntime(PersonalLivePolicy policy, PersonalLiveRuntimeResources shared,
            AgentStore requests, LiveRequestStore links, ProjectAuthorization projects, CurrentActorProvider actors,
            AuthenticatedTaskScope identities, TraceTaskScope traces, TraceIdProvider traceIds,
            AuditRecorder audit, Clock clock, PlatformTransactionManager manager, LiveAccountingOperations accounting,
            LiveDatabaseWork database, AgentOutputValidator validator, ScanJobOperations scans,
            FindingOperations findings, DocumentOperations documents, ReportSubmissionOperations reports) {
        var executor = shared.executor();
        Runnable configured = () -> { executor.requireOpen(); policy.requireConfigured(); };
        Runnable active = () -> { executor.requireOpen(); identities.requireActive(); };
        var resources = new LiveResourceAuthorizer(projects, scans, findings, documents, reports);
        var results = new LiveResultTransitions(requests, audit, clock, manager, database);
        var execution = new LiveExecutionCoordinator(requests, accounting, snapshot -> {
            // Core invokes this only inside its original bounded read transaction, never a Key callback.
            var view = snapshot.view();
            var link = links.find(view.projectId(), view.id()).orElseThrow(AgentEnablementInvalidStateException::new);
            policy.requireBatch(view.projectId(), link.batchId(), link.templateId());
            resources.validate(snapshot);
        }, shared.responses(configured), shared.credentials(configured), results, validator, actors, body -> {
            configured.run();
            if (body == null || body.length == 0 || body.length > 6000) throw new AgentEnablementInvalidStateException();
        }, System::nanoTime, database, active);
        var claims = new LiveDispatchTransitions(requests, links, projects, actors, identities, traceIds,
            audit, clock, manager, accounting, database);
        var dispatcher = new LiveRequestDispatcher(requests, links, actors, identities, traces, claims,
            execution::execute, executor, configured, System::nanoTime);
        return new PersonalLiveRuntime(shared, dispatcher, configured);
    }
}
