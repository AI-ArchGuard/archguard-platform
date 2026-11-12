package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.common.*;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.identity.*;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import java.time.Clock;
import org.springframework.transaction.PlatformTransactionManager;

/** 默认拒绝装配；此接缝不注册 HTTP 或调用凭据。 */
final class PersonalDailyRuntimeConfiguration {
    PersonalLiveRuntime personalDailyRuntime(PersonalLiveRuntimeResources resources, DailyRuntimePolicy policy,
            AgentStore requests, DailyAgentStore daily, DailyAgentControlService controls,
            DailyAccountingService accounting, ProjectAuthorization projects, CurrentActorProvider actors,
            AuthenticatedTaskScope identities, TraceTaskScope traces, TraceIdProvider traceIds,
            AuditRecorder audit, Clock clock, PlatformTransactionManager manager, LiveDatabaseWork database,
            AgentOutputValidator validator, ScanJobOperations jobs, FindingOperations findings,
            DocumentOperations documents, ReportSubmissionOperations reports) {
        throw new AgentUnavailableException();
    }
}
