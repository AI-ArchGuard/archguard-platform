package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import java.util.function.*;

/** Unassembled RED boundary. No request creation, transaction, scheduling or runtime bean yet. */
public final class DailyAgentRequestService {
    public DailyAgentRequestService(ProjectAuthorization projects, CurrentActorProvider actors,
            DailyAgentControlService controls, AgentStore requests, DailyAgentStore daily,
            ScanJobOperations jobs, ReportSubmissionOperations reports, FindingOperations findings,
            DocumentOperations documents, AuditRecorder audit, TraceIdProvider traces, ObjectMapper mapper,
            Clock clock, LiveDatabaseWork work, Function<AgentModelPort.ModelInput, byte[]> preparation,
            Consumer<DailyAgentStore.RequestLink> afterCommit) {}
    public Creation<DailyRequestView> create(UUID project, String key, DailyRequestInput input) {
        throw new AgentUnavailableException();
    }
    public Creation<DailyRequestView> create(UUID project, String key, DailyRequestInput input, LongSupplier remaining) {
        throw new AgentUnavailableException();
    }
}
