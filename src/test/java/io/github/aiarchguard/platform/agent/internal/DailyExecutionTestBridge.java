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
    private final AuthenticatedTaskScope scope;
    public DailyExecutionTestBridge(DailyAgentStore store, DailyAgentControlService controls,
            DailyAccountingService accounting, Consumer<AgentSnapshot> resources, AuthenticatedTaskScope scope) {
        this.scope = scope;
        association = new DailyLiveRequestAssociation(store, controls, accounting, resources);
    }

    /** 观察清理事务时仍调用生产恢复入口，构造能力只在测试包内暴露。 */
    public static DailyAgentRecovery recovery(DailyAccountingService accounting) {
        return new DailyAgentRecovery(accounting);
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

    public static void afterCommit(LiveRequestDispatcher dispatcher,DailyAgentStore.RequestLink link) {
        dispatcher.dispatchCommitted(link.projectId(),link.requestId());
    }

    /** 保留生产适配器的销毁元数据，只将实例换成无网络计数器，再关闭独立子上下文。 */
    public static void verifySpringOwnership(org.springframework.context.ConfigurableApplicationContext parent) {
        var bundle=new PersonalLiveExecutorTest.FakeBundle();
        var child=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        child.setParent(parent);
        var source=parent.getBeanFactory();
        String name=source.getBeanNamesForType(LiveAdapterBundle.class,false,false)[0];
        var definition=new org.springframework.beans.factory.support.GenericBeanDefinition(source.getBeanDefinition(name));
        definition.setBeanClass(PersonalLiveExecutorTest.FakeBundle.class);
        definition.setFactoryBeanName(null); definition.setFactoryMethodName(null);
        definition.setInstanceSupplier(()->bundle); child.registerBeanDefinition(name,definition);
        child.register(PersonalLiveRuntimeConfiguration.class,PersonalDailyRuntimeConfiguration.class);
        try {
            child.refresh();
            var owner=child.getBean(PersonalLiveRuntimeResources.class);
            var legacy=child.getBean("personalLiveRuntime",PersonalLiveRuntime.class);
            var daily=child.getBean("personalDailyRuntime",PersonalLiveRuntime.class);
            for(var facade:new PersonalLiveRuntime[]{legacy,daily}) {
                var dispatcher=org.springframework.test.util.ReflectionTestUtils.getField(facade,"dispatcher");
                org.assertj.core.api.Assertions.assertThat(org.springframework.test.util.ReflectionTestUtils.getField(dispatcher,"executor"))
                    .isSameAs(owner.executor());
                facade.close(); owner.requireOpen();
            }
            org.assertj.core.api.Assertions.assertThat(owner.adapters()).isSameAs(bundle);
            org.assertj.core.api.Assertions.assertThat(bundle.closes).hasValue(0);
        } finally { child.close(); }
        org.assertj.core.api.Assertions.assertThat(bundle.closes).hasValue(1);
        org.assertj.core.api.Assertions.assertThat(bundle.awaited).isTrue();
        org.assertj.core.api.Assertions.assertThat(bundle.responses).hasValue(0);
        org.assertj.core.api.Assertions.assertThat(bundle.credentials).hasValue(0);
    }

    /** 正向用例从 Spring 取得真实创建服务。子上下文遮蔽生产适配器、捕获测试身份，使用完整合成配置。 */
    public static org.springframework.context.annotation.AnnotationConfigApplicationContext assembled(
            org.springframework.context.ConfigurableApplicationContext parent, LiveAdapterBundle bundle,
            AuthenticatedTaskScope identities) {
        var child=new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        child.setParent(parent); child.setEnvironment(DailyControlTestConfiguration.ENV);
        var beanFactory=parent.getBeanFactory();
        String[] adapterNames=beanFactory.getBeanNamesForType(LiveAdapterBundle.class,false,false);
        org.assertj.core.api.Assertions.assertThat(adapterNames).hasSize(1);
        child.registerBean(adapterNames[0],LiveAdapterBundle.class,()->bundle,bd->bd.setPrimary(true));
        child.getBeanFactory().registerResolvableDependency(AuthenticatedTaskScope.class,identities);
        child.register(PersonalLiveRuntimeConfiguration.class,PersonalDailyRuntimeConfiguration.class);
        try {
            child.refresh();
            var owner=child.getBean(PersonalLiveRuntimeResources.class);
            org.assertj.core.api.Assertions.assertThat(owner.adapters()).isSameAs(bundle);
            org.assertj.core.api.Assertions.assertThat(child.getBean(LiveAdapterBundle.class)).isSameAs(bundle);
            return child;
        } catch(RuntimeException|Error failure) { child.close(); throw failure; }
    }

    /** 使用待测生产装配，仅替换适配器包与明确的合成运行配置。 */
    public static final class Runtime implements AutoCloseable {
        private final PersonalLiveRuntimeResources resources;
        private final PersonalLiveRuntime runtime;
        public Runtime(LiveAdapterBundle adapters, javax.sql.DataSource source,
                io.github.aiarchguard.platform.agentcredential.CredentialVersionSource versions,
                AgentStore requests, DailyAgentStore daily, DailyAgentControlService controls,
                DailyAccountingService accounting, ProjectAuthorization projects, CurrentActorProvider actors,
                AuthenticatedTaskScope identities, TraceIdProvider traceIds, AuditRecorder audit, Clock clock,
                PlatformTransactionManager manager, LiveDatabaseWork work, AgentOutputValidator validator,
                io.github.aiarchguard.platform.scanjob.ScanJobOperations jobs,
                io.github.aiarchguard.platform.finding.FindingOperations findings,
                io.github.aiarchguard.platform.agentdocument.DocumentOperations documents,
                io.github.aiarchguard.platform.governance.ReportSubmissionOperations reports) {
            resources=new PersonalLiveRuntimeResources(adapters);
            var policy=new DailyRuntimePolicy(DailyControlTestConfiguration.ENV,source,versions,clock);
            runtime=new PersonalDailyRuntimeConfiguration().personalDailyRuntime(resources,policy,requests,daily,
                controls,accounting,projects,actors,identities,new TraceTaskScope(),traceIds,audit,clock,manager,work,
                validator,jobs,findings,documents,reports);
        }
        public void dispatch(DailyAgentStore.RequestLink link) { runtime.dispatch(link.projectId(),link.requestId()); }
        /** 将栅栏放在实际共享队列尾端，避免通过关闭丢弃任务产生假阴性。 */
        public void awaitIdle() {
            var done=new java.util.concurrent.CountDownLatch(1);
            resources.executor().execute(done::countDown);
            try { org.assertj.core.api.Assertions.assertThat(done.await(6,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
            catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
        public void close() { resources.close(); }
    }

    public void finish(LiveResultTransitions writer, AgentSnapshot snapshot, AgentRequestView.AgentResult result,
            AgentRequestView.AgentUsage usage, LongSupplier remaining) {
        var view = snapshot.view();
        var binding = association.find(view.projectId(), view.id()).orElseThrow();
        writer.finishBounded(snapshot,result,usage,remaining,association.lease(binding),scope::requireActive);
    }
}
