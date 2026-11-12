package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.infrastructure.DeepSeekRequestPreparation;
import io.github.aiarchguard.platform.agentcredential.CredentialVersionSource;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.common.*;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.identity.*;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/** 日常运行仅从提交成功的创建入口交接；生产适配器与批次共享，模式准入分别核验。 */
@Configuration(proxyBeanMethods = false)
final class PersonalDailyRuntimeConfiguration {
    @Bean
    DailyRuntimePolicy dailyRuntimePolicy(Environment environment, DataSource dataSource, CredentialVersionSource versions, Clock clock) {
        return new DailyRuntimePolicy(environment, dataSource, versions, clock);
    }

    @Bean
    DailyAgentRequestService dailyAgentRequestService(ProjectAuthorization projects, CurrentActorProvider actors,
            DailyAgentControlService controls, AgentStore requests, DailyAgentStore daily, ScanJobOperations jobs,
            ReportSubmissionOperations reports, FindingOperations findings, DocumentOperations documents,
            AuditRecorder audit, TraceIdProvider traces, ObjectMapper mapper, Clock clock, LiveDatabaseWork database,
            @Qualifier("personalDailyRuntime") PersonalLiveRuntime runtime) {
        var encoder = new DeepSeekRequestPreparation();
        return new DailyAgentRequestService(projects, actors, controls, requests, daily, jobs, reports, findings,
            documents, audit, traces, mapper, clock, database, encoder::prepare,
            link -> runtime.dispatchCommitted(link.projectId(), link.requestId()));
    }

    @Bean(destroyMethod = "")
    PersonalLiveRuntime personalDailyRuntime(PersonalLiveRuntimeResources resources, DailyRuntimePolicy policy,
            AgentStore requests, DailyAgentStore daily, DailyAgentControlService controls,
            DailyAccountingService accounting, ProjectAuthorization projects, CurrentActorProvider actors,
            AuthenticatedTaskScope identities, TraceTaskScope traces, TraceIdProvider traceIds,
            AuditRecorder audit, Clock clock, PlatformTransactionManager manager, LiveDatabaseWork database,
            AgentOutputValidator validator, ScanJobOperations jobs, FindingOperations findings,
            DocumentOperations documents, ReportSubmissionOperations reports) {
        Runnable configured = () -> { resources.requireOpen(); policy.requireConfigured(); };
        Runnable active = () -> { resources.requireOpen(); identities.requireActive(); };
        var authorization = new LiveResourceAuthorizer(projects, jobs, findings, documents, reports);
        var association = new DailyLiveRequestAssociation(daily, controls, accounting, authorization::validateDaily);
        var claims = new LiveDispatchTransitions(requests, association, projects, actors, identities, traceIds,
            audit, clock, manager, database);
        var results = new LiveResultTransitions(requests, audit, clock, manager, database);
        // 日常执行仅通过精确 lease 记账；不会取得或借用旧批次的预留能力。
        var core = new LiveExecutionCoordinator(requests, null, authorization::validateDaily,
            resources.responses(configured), resources.credentials(configured), results, validator, actors, body -> {
                configured.run();
                if (body == null || body.length == 0 || body.length > 6000) throw new AgentEnablementInvalidStateException();
            }, System::nanoTime, database, active);
        var dispatcher = new LiveRequestDispatcher(requests, association, actors, identities, traces, claims,
            core::execute, resources.executor(), configured, System::nanoTime);
        return new PersonalLiveRuntime(resources, dispatcher, configured);
    }
}
