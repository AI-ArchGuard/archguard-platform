package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentEnablementInvalidStateException;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import io.github.aiarchguard.platform.agent.AgentModelPort;
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
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Wires controlled same-package capabilities without creating any client or thread at startup. */
@Configuration
class PersonalLiveRuntimeConfiguration {
    @Bean(destroyMethod = "close")
    PersonalLiveRuntime personalLiveRuntime(PersonalLivePolicy policy, LiveAdapterBundle adapters,
            AgentStore requests, LiveRequestStore links, ProjectAuthorization projects, CurrentActorProvider actors,
            AuthenticatedTaskScope identities, TraceTaskScope traces, TraceIdProvider traceIds,
            AuditRecorder audit, Clock clock, PlatformTransactionManager manager, LiveAccountingOperations accounting,
            LiveDatabaseWork database, AgentOutputValidator validator, ScanJobOperations scans,
            FindingOperations findings, DocumentOperations documents, ReportSubmissionOperations reports) {
        var executor = new PersonalLiveExecutor(adapters);
        Runnable configured = () -> { executor.requireOpen(); policy.requireConfigured(); };
        Runnable active = () -> { executor.requireOpen(); identities.requireActive(); };
        var resources = new LiveResourceAuthorizer(projects, scans, findings, documents, reports);
        LiveResponsePort responses = new LiveResponsePort() {
            @Override public byte[] prepare(AgentModelPort.ModelInput input) {
                configured.run(); return adapters.responses().prepare(input);
            }
            @Override public Reply send(byte[] body, String key, Duration timeout, Set<String> models) {
                throw new CallFailure("MODEL_UNAVAILABLE");
            }
            @Override public Reply send(byte[] body, String key, Duration timeout, Set<String> models, String price) {
                configured.run();
                if (!LiveCostPolicy.CONTEXT_VERSION.equals(price)) throw new CallFailure("MODEL_UNAVAILABLE");
                return adapters.responses().send(body, key, timeout, models, price);
            }
        };
        LiveExecutionCoordinator.CredentialUse credentials = new LiveExecutionCoordinator.CredentialUse() {
            @Override public LiveResponsePort.Reply use(UUID version, Runnable preflight,
                    Function<String, LiveResponsePort.Reply> send) {
                throw new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
            }
            @Override public LiveResponsePort.Reply use(UUID version, LongSupplier remaining, Runnable preflight,
                    Function<String, LiveResponsePort.Reply> send) {
                configured.run(); return adapters.credentials().use(version, remaining, preflight, send);
            }
        };
        var results = new LiveResultTransitions(requests, audit, clock, manager, database);
        var execution = new LiveExecutionCoordinator(requests, accounting, snapshot -> {
            // Core invokes this only inside its original bounded read transaction, never a Key callback.
            var view = snapshot.view();
            var link = links.find(view.projectId(), view.id()).orElseThrow(AgentEnablementInvalidStateException::new);
            policy.requireBatch(view.projectId(), link.batchId(), link.templateId());
            resources.validate(snapshot);
        }, responses, credentials, results, validator, actors, body -> {
            configured.run();
            if (body == null || body.length == 0 || body.length > 6000) throw new AgentEnablementInvalidStateException();
        }, System::nanoTime, database, active);
        var claims = new LiveDispatchTransitions(requests, links, projects, actors, identities, traceIds,
            audit, clock, manager, accounting, database);
        var dispatcher = new LiveRequestDispatcher(requests, links, actors, identities, traces, claims,
            execution::execute, executor, configured, System::nanoTime);
        return new PersonalLiveRuntime(executor, dispatcher, configured);
    }
}
