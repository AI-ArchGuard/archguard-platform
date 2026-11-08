package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.common.*;
import io.github.aiarchguard.platform.identity.*;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.*;
import org.springframework.transaction.PlatformTransactionManager;

/** 仅供不同测试包手工装配真实日常关联。生产代码不导出 lease，也不注册 worker。 */
public final class DailyExecutionTestBridge {
    private final LiveRequestAssociation association;
    public DailyExecutionTestBridge(DailyAgentStore store, DailyAgentControlService controls,
            DailyAccountingService accounting, Consumer<AgentSnapshot> resources) {
        association = new DailyLiveRequestAssociation(store, controls, accounting, resources);
    }

    /** 故意以缺少资源校验器的构造申请能力，供集成测试核验没有任何写副作用。 */
    public static void tryUnverifiedLease(DailyAgentStore store, DailyAgentControlService controls,
            DailyAccountingService service, DailyAgentStore.RequestLink link) {
        new DailyLiveRequestAssociation(store,controls,service).lease(new DailyLiveBinding(link));
    }

    public LiveDispatchTransitions transitions(AgentStore requests, ProjectAuthorization projects, CurrentActorProvider actors,
            AuthenticatedTaskScope scope, TraceIdProvider traces, AuditRecorder audit, Clock clock,
            PlatformTransactionManager manager, LiveDatabaseWork database) {
        return new LiveDispatchTransitions(requests, association, projects, actors, scope, traces, audit, clock, manager, database);
    }

    public LiveRequestDispatcher dispatcher(AgentStore requests, CurrentActorProvider actors, AuthenticatedTaskScope scope,
            LiveDispatchTransitions transitions, Executor queue, BiConsumer<UUID,LongSupplier> execution) {
        return new LiveRequestDispatcher(requests, association, actors, scope, new TraceTaskScope(), transitions,
            (lease,left) -> execution.accept(lease.requestId(),left), queue, () -> {}, System::nanoTime);
    }

    /** 使用真实协调器、日常账本和结果事务。凭据与发送端口只接受离线测试替身。 */
    public LiveRequestDispatcher coreDispatcher(AgentStore requests, CurrentActorProvider actors, AuthenticatedTaskScope scope,
            LiveDispatchTransitions transitions, Consumer<AgentSnapshot> resources, LiveResponsePort port,
            LiveResultTransitions writer, AgentOutputValidator validator, LiveDatabaseWork database) {
        var credentials = new LiveExecutionCoordinator.CredentialUse() {
            public LiveResponsePort.Reply use(UUID version,Runnable preflight,Function<String,LiveResponsePort.Reply> op) {
                throw new AssertionError("不应选择无界凭据路径");
            }
            public LiveResponsePort.Reply use(UUID version,LongSupplier left,Runnable preflight,Function<String,LiveResponsePort.Reply> op) {
                if (left.getAsLong()<=0) throw new AssertionError("凭据期限耗尽");
                preflight.run(); return op.apply("synthetic-placeholder");
            }
        };
        var core=new LiveExecutionCoordinator(requests,null,resources,port,credentials,writer,validator,actors,
            body->{ if (body.length==0 || body.length>6000) throw new AssertionError("无效请求长度"); },
            System::nanoTime,database,scope::requireActive);
        return new LiveRequestDispatcher(requests,association,actors,scope,new TraceTaskScope(),transitions,
            core::execute,Runnable::run,()->{},System::nanoTime);
    }

    public void finish(LiveResultTransitions writer, AgentSnapshot snapshot, AgentRequestView.AgentResult result,
            AgentRequestView.AgentUsage usage, LongSupplier remaining) {
        var view = snapshot.view();
        var binding = association.find(view.projectId(), view.id()).orElseThrow();
        writer.finishBounded(snapshot,result,usage,remaining,association.lease(binding));
    }
}
