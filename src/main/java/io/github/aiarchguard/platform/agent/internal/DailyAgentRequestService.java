package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.*;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.audit.*;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.function.*;

/** 日常创建的单一有界事务；仅在真实提交后通知未装配的调度端口。 */
public final class DailyAgentRequestService {
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final DailyAgentControlService controls;
    private final AgentStore requests;
    private final DailyAgentStore daily;
    private final AuthorizedAgentSnapshotBuilder builder;
    private final AuditRecorder audit;
    private final TraceIdProvider traces;
    private final Clock clock;
    private final LiveDatabaseWork work;
    private final Function<AgentModelPort.ModelInput, byte[]> preparation;
    private final Consumer<DailyAgentStore.RequestLink> afterCommit;
    public DailyAgentRequestService(ProjectAuthorization projects, CurrentActorProvider actors,
            DailyAgentControlService controls, AgentStore requests, DailyAgentStore daily,
            ScanJobOperations jobs, ReportSubmissionOperations reports, FindingOperations findings,
            DocumentOperations documents, AuditRecorder audit, TraceIdProvider traces, ObjectMapper mapper,
            Clock clock, LiveDatabaseWork work, Function<AgentModelPort.ModelInput, byte[]> preparation,
            Consumer<DailyAgentStore.RequestLink> afterCommit) {
        this.projects=projects; this.actors=actors; this.controls=controls; this.requests=requests; this.daily=daily;
        this.builder=new AuthorizedAgentSnapshotBuilder(jobs,reports,findings,documents,mapper);
        this.audit=audit; this.traces=traces; this.clock=clock; this.work=work; this.preparation=preparation; this.afterCommit=afterCommit;
    }
    public Creation<DailyRequestView> create(UUID project, String key, DailyRequestInput input) {
        return create(project,key,input,new LiveExecutionBudget(System::nanoTime));
    }
    public Creation<DailyRequestView> create(UUID project, String key, DailyRequestInput input, LongSupplier remaining) {
        return work.transaction(remaining,false,() -> {
            controls.authorizeRequestRead(project); var selection=validate(key,input); UUID owner=actors.currentActor().id(); check(remaining);
            var old=requests.findByKey(project,owner,key);
            if(old.isPresent()) return replay(project,input.grantId(),selection,old.get());
            daily.lockSettings(project); check(remaining);
            // 未知授权不应遮蔽等锁期间刚提交的同键历史；不存在的 usage 留给后续当前授权检查。
            if(daily.findGrant(project,input.grantId()).isPresent()) daily.lockUsage(project,input.grantId());
            controls.authorizeRequestRead(project); check(remaining);
            old=requests.findByKey(project,owner,key);
            if(old.isPresent()) return replay(project,input.grantId(),selection,old.get());
            if("PR_SUMMARY".equals(selection.purpose())) projects.requireMaintainer(project);
            var grant=controls.requireCurrent(project,input.grantId(),selection.purpose(),remaining);
            if(grant.usage().activeRequestId()!=null) throw new AgentDailyActionInProgressException();
            var selected=builder.authorize(project,selection); check(remaining);
            String digest=builder.dailyDigest(project,owner,grant.id(),grant.runtimeBindingDigest(),selected,AuthorizedAgentSnapshotBuilder.DAILY);
            AgentSnapshot snapshot=builder.build(selected,new AuthorizedAgentSnapshotBuilder.SnapshotIdentity(UUID.randomUUID(),owner,traces.currentTraceId(),clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)),AuthorizedAgentSnapshotBuilder.DAILY,digest);
            String payloadHash, candidatesHash;
            try {
                byte[] body=preparation.apply(snapshot.input());
                if(body==null || body.length>6000) throw new AgentInvalidException();
                payloadHash=DailySnapshotHashes.sha256(snapshot.input()); candidatesHash=DailySnapshotHashes.sha256(snapshot.candidates());
            } catch(RuntimeException invalid) { throw new AgentInvalidException(); }
            var current=controls.requireCurrent(project,grant.id(),selection.purpose(),remaining); check(remaining);
            if(!current.runtimeBindingDigest().equals(grant.runtimeBindingDigest()) || !current.credentialVersion().equals(grant.credentialVersion())) throw new AgentDailyStateInvalidException();
            var settings=daily.settings(project);
            if(!requests.insert(snapshot,key)) return replay(project,input.grantId(),selection,
                requests.findByKey(project,owner,key).orElseThrow(AgentUnavailableException::new));
            var link=new DailyAgentStore.RequestLink(snapshot.view().id(),project,grant.id(),grant.riskAcknowledgementId(),grant.credentialVersion(),
                settings.revision(),grant.runtimeBindingDigest(),payloadHash,candidatesHash,snapshot.view().createdAt());
            daily.insertRequest(link); daily.acquireActive(project,grant.id(),link.requestId());
            audit.record(new AuditEvent(owner,project,"agent.daily.request.created",AuditResult.SUCCESS,traces.currentTraceId(),
                Map.of("requestId",link.requestId(),"grantId",grant.id())));
            check(remaining);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { check(remaining); }
                @Override public void afterCommit() { afterCommit.accept(link); }
            });
            return new Creation<>(view(snapshot,link),true);
        });
    }
    private Creation<DailyRequestView> replay(UUID project,UUID grant,CreateAgentRequest selection,AgentSnapshot stored) {
        var link=daily.findRequest(project,stored.view().id()).orElseThrow(AgentConflictException::new);
        if(!grant.equals(link.grantId()) || !selection.purpose().equals(stored.view().purpose())
                || !builder.dailyReplayDigest(project,stored.view().requesterId(),grant,link.runtimeBindingDigest(),selection,stored.view().bindings())
                    .equals(stored.view().bindings().inputDigest())) throw new AgentConflictException();
        return new Creation<>(view(stored,link),false);
    }
    private DailyRequestView view(AgentSnapshot snapshot,DailyAgentStore.RequestLink link) {
        return new DailyRequestView("0.1.0",snapshot.view(),new DailyAuthorization(link.grantId(),link.riskAcknowledgementId(),link.runtimeBindingDigest(),link.settingsRevision()),
            new DailyExecution(null,null,DailyCostState.NOT_ATTEMPTED,null,null));
    }
    private static void check(LongSupplier remaining) {
        if(remaining==null || Thread.currentThread().isInterrupted() || remaining.getAsLong()<=0) throw new AgentUnavailableException();
    }
    private static CreateAgentRequest validate(String key,DailyRequestInput input) {
        if(key==null || !key.matches("[!-~]{1,128}") || input==null || !"0.1.0".equals(input.schemaVersion())
                || input.grantId()==null || !Boolean.TRUE.equals(input.selectedMaterialConfirmed()) || input.selection()==null) throw new AgentInvalidException();
        var s=input.selection(); boolean summary=s.purpose()==DailyPurpose.PR_SUMMARY;
        if(s.purpose()==null || s.scanJobId()==null || s.reportSha256()==null || !s.reportSha256().matches("[a-f0-9]{64}")
                || (summary ? s.prHeadRevisionId()==null : s.prHeadRevisionId()!=null)
                || !ids(s.findingIds(),1,summary?20:1) || !ids(s.documentVersionIds(),0,10)) throw new AgentInvalidException();
        return new CreateAgentRequest(s.purpose().name(),s.scanJobId(),s.reportSha256(),s.prHeadRevisionId(),
            s.findingIds().stream().sorted().toList(),s.documentVersionIds().stream().sorted().toList());
    }
    private static boolean ids(List<UUID> values,int min,int max) {
        return values!=null && values.size()>=min && values.size()<=max && values.stream().noneMatch(Objects::isNull)
            && new HashSet<>(values).size()==values.size();
    }
}
