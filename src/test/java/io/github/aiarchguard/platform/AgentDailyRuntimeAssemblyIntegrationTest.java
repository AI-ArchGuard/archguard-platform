package io.github.aiarchguard.platform;

import static io.github.aiarchguard.platform.agent.internal.DailyControlTestConfiguration.*;
import static org.assertj.core.api.Assertions.*;
import io.github.aiarchguard.platform.agent.*;
import io.github.aiarchguard.platform.agent.DailyAgentOperations.DailySettingsInput;
import io.github.aiarchguard.platform.agent.infrastructure.*;
import io.github.aiarchguard.platform.agent.internal.*;
import io.github.aiarchguard.platform.agentcredential.*;
import io.github.aiarchguard.platform.agentcredential.internal.SyntheticRuntimeCredentialFiles;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.identity.AuthenticatedTaskScope;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.*;

/** 使用真实创建事务及固定编解码；测试假交换只处理内存 HttpRequest，不能打开网络。 */
@org.springframework.context.annotation.Import(AgentDailyRuntimeAssemblyIntegrationTest.Isolation.class)
class AgentDailyRuntimeAssemblyIntegrationTest extends DailyExecutionIntegrationSupport {
    /** 替换实际注册的 bundle 实例并保留销毁元数据；父、子上下文中均不可构造真实传输。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class Isolation {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanFactoryPostProcessor offlineOnly() {
            return factory->{
                var names=factory.getBeanNamesForType(LiveAdapterBundle.class,false,false);
                assertThat(names).hasSize(1);
                var definition=(org.springframework.beans.factory.support.AbstractBeanDefinition)factory.getBeanDefinition(names[0]);
                definition.setBeanClass(Offline.class); definition.setInstanceSupplier(Offline::new);
            };
        }
    }
    static final class Offline implements LiveAdapterBundle {
        volatile LiveAdapterBundle delegate;
        LiveAdapterBundle current() {
            if(delegate==null) throw new AssertionError("初始化不应访问网络或凭据");
            return delegate;
        }
        public LiveResponsePort responses() { return current().responses(); }
        public LiveExecutionCoordinator.CredentialUse credentials() { return current().credentials(); }
        public void close() { if(delegate!=null) delegate.close(); }
        public boolean awaitTermination(Duration timeout) throws InterruptedException {
            return delegate==null || delegate.awaitTermination(timeout);
        }
    }
    @org.junit.jupiter.api.BeforeEach void installProbe() {
        ((Offline)context.getBean(LiveAdapterBundle.class)).delegate=bundle;
    }
    static final SyntheticRuntimeCredentialFiles.Paths FILES=SyntheticRuntimeCredentialFiles.create();
    @DynamicPropertySource static void files(DynamicPropertyRegistry registry) {
        registry.add("archguard.agent.credentials.directory",()->FILES.directory().toString());
        registry.add("archguard.agent.credentials.master-file",()->FILES.master().toString());
    }
    @Autowired org.springframework.context.ConfigurableApplicationContext context;
    @Test void springClosesTheSharedBundleExactlyOnceAndNeitherBorrowerOwnsIt() {
        DailyExecutionTestBridge.verifySpringOwnership(context);
    }
    @Autowired CredentialOperations credentialOperations;
    @Autowired CredentialVersionSource versions;
    final AtomicInteger consumes=new AtomicInteger(), initializes=new AtomicInteger();
    final AtomicReference<SyntheticRuntimeLiveAdapter> adapter=new AtomicReference<>();
    final AuthenticatedTaskScope workerIdentity=new AuthenticatedTaskScope() {
        public Runnable capture(UUID expected,Runnable task) {
            assertThat(actors.currentActor().id()).isEqualTo(expected);
            return ()->{ try { asOwner(()->{task.run();return null;}).call(); }
                catch(Exception failure) { throw new IllegalStateException(failure); } };
        }
        public void requireActive() { scope.requireActive(); }
    };
    final LiveAdapterBundle bundle=new LiveAdapterBundle() {
        synchronized SyntheticRuntimeLiveAdapter get() {
            if(adapter.get()==null) {
                initializes.incrementAndGet();
                adapter.set(new SyntheticRuntimeLiveAdapter(mapper,new CredentialUseSource() {
                    public void consume(UUID version,Consumer<String> callback) { throw new AssertionError("禁止无界消费"); }
                    public void consume(UUID version,LongSupplier remaining,Consumer<String> callback) {
                        assertThat(version).isEqualTo(VERSION); assertThat(remaining.getAsLong()).isPositive();
                        consumes.incrementAndGet(); callback.accept(SyntheticRuntimeLiveAdapter.KEY);
                    }
                }));
            }
            return adapter.get();
        }
        public LiveResponsePort responses() { return get().responses(); }
        public LiveExecutionCoordinator.CredentialUse credentials() { return get().credentials(); }
        public void close() { if(adapter.get()!=null) adapter.get().close(); }
        public boolean awaitTermination(Duration timeout) { return true; }
    };
    DailyExecutionTestBridge.Runtime runtime() {
        return new DailyExecutionTestBridge.Runtime(bundle,source,versions,requests,store,controls,service,projects,
            actors,workerIdentity,traces,audit,clock,manager,work,new AgentOutputValidator(mapper),jobs,findings,documents,reports);
    }
    DailyAgentRequestService creator(AuditRecorder recorder,Consumer<DailyAgentStore.RequestLink> submit) {
        return new DailyAgentRequestService(projects,actors,controls,requests,store,jobs,reports,findings,documents,
            recorder,traces,mapper,clock,work,new DeepSeekRequestPreparation()::prepare,submit);
    }
    @Test void committedCreationTraversesAssembledRuntimeAndFakeHttpOnce() throws Exception {
        org.slf4j.MDC.put("traceId","d".repeat(32));
        var g=grant(facts.project(),2,2*R);
        try(var assembled=DailyExecutionTestBridge.assembled(context,bundle,workerIdentity)) {
            assertThat(assembled.getBeanFactory().getBeanNamesForType(DailyAgentRequestService.class)).hasSize(1);
            var creator=assembled.getBean(DailyAgentRequestService.class);
            var made=creator.create(facts.project(),"runtime",input(facts,g));
            var c=new Case(facts.project(),g,made.value().request().id());
            assertThat(creator.create(c.p(),"runtime",input(facts,g)).created()).isFalse();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            while(System.nanoTime()<until && Set.of("QUEUED","RUNNING").contains(state(c))) Thread.sleep(10);
            assertThat(state(c)).isEqualTo("SUCCEEDED");
            assertThat(usage(c)).isEqualTo(new DailyAgentStore.Usage(1,0,273,null));
            assertThat(audits(c,"agent.daily.request.running")).isOne();
            assertThat(consumes).hasValue(1); assertThat(initializes).hasValue(1); assertThat(adapter.get().calls).hasValue(1);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void realCreationCommitQueuesExactlyOnceAndRollbackQueuesNothing(boolean rollback) {
        org.slf4j.MDC.put("traceId","d".repeat(32)); var p=facts.project(); var g=grant(p,2,2*R);
        var jobs=new ArrayList<Runnable>(); var core=new AtomicInteger(); var reached=new AtomicBoolean();
        var dispatch=bridge().dispatcher(requests,actors,scope,transitions(audit),jobs::add,(request,left)->{
            assertThat(left.getAsLong()).isPositive(); core.incrementAndGet();
        });
        var creator=creator(event->{audit.record(event); if(rollback) TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() { public void beforeCommit(boolean readOnly) { reached.set(true); throw new AgentUnavailableException(); } });
        },link->DailyExecutionTestBridge.afterCommit(dispatch,link));
        if(rollback) {
            assertThatThrownBy(()->creator.create(p,"commit-phase",input(facts,g))).isInstanceOf(AgentUnavailableException.class);
            assertThat(reached).isTrue(); assertThat(jobs).isEmpty(); assertThat(core).hasValue(0);
            assertThat(requests.findByKey(p,OWNER,"commit-phase")).isEmpty();
            assertThat(store.findGrant(p,g).orElseThrow().usage()).isEqualTo(new DailyAgentStore.Usage(0,0,0,null));
        } else {
            var made=creator.create(p,"commit-phase",input(facts,g)); assertThat(made.created()).isTrue();
            assertThat(creator.create(p,"commit-phase",input(facts,g)).created()).isFalse();
            assertThat(jobs).hasSize(1); jobs.getFirst().run(); assertThat(core).hasValue(1);
            assertThat(state(new Case(p,g,made.value().request().id()))).isEqualTo("RUNNING");
        }
    }

    @ParameterizedTest @ValueSource(strings={"none","total","live","daily","grant","secret","network","source"})
    void isolatedSwitchOrIncompleteBindingCannotInitializeProvider(String gate) {
        org.slf4j.MDC.put("traceId","d".repeat(32));
        var c=queued(2); var link=store.findRequest(c.p(),c.r()).orElseThrow();
        boolean incomplete=Set.of("secret","network","source").contains(gate);
        ENV.setProperty("archguard.agent.enabled",Boolean.toString(incomplete||gate.equals("total")));
        ENV.setProperty("archguard.agent.live.enabled",Boolean.toString(gate.equals("live")));
        ENV.setProperty("archguard.agent.daily.enabled",Boolean.toString(incomplete||gate.equals("daily")));
        if(!incomplete&&!gate.equals("grant")) controls.revokeGrant(c.p(),c.g());
        if(incomplete) ENV.setProperty("archguard.agent.daily."+switch(gate){
            case "secret"->"secret-check-ref"; case "network"->"network-check-ref"; default->"evidence.sources[0].version";},"");
        try(var runtime=runtime()) {
            // 有些失效条件要到领取才发现；等待队尾栅栏后再核对副作用。
            try { runtime.dispatch(link); } catch (AgentUnavailableException denied) { assertThat(denied).hasNoCause(); }
            runtime.awaitIdle();
        }
        assertThat(initializes).hasValue(0); assertThat(consumes).hasValue(0); assertThat(adapter).hasNullValue();
        assertThat(store.attempt(c.p(),c.r())).isEmpty();
    }
    @Test void closedDeploymentContextSettingsAndSavedSyntheticKeyHaveZeroSideEffects() {
        var p=facts.project();
        ENV.setProperty("archguard.agent.enabled","false");
        ENV.setProperty("archguard.agent.daily.enabled","false");
        ENV.setProperty("archguard.agent.daily.runtime-revision","");
        try(var runtime=runtime()) {
            var context=controls.context(p);
            assertThat(context.runtimeReady()).isFalse(); assertThat(context.runtimeBinding()).isNull();
            assertThat(controls.settings(p).enabled()).isFalse();
            assertThat(credentialOperations.write(SyntheticRuntimeLiveAdapter.KEY).configured()).isTrue();
            assertThat(controls.settings(p).modelCallsAvailable()).isFalse();
            assertThat(consumes).hasValue(0); assertThat(initializes).hasValue(0); assertThat(adapter).hasNullValue();
        }
    }
    @Test void controlsAndHistoricalGrantReadsNeverOpenProviderOrUseKey() {
        org.slf4j.MDC.put("traceId","d".repeat(32)); var p=facts.project();
        try(var runtime=runtime()) {
            assertThat(controls.context(p).settings().enabled()).isFalse();
            assertThat(credentialOperations.write(SyntheticRuntimeLiveAdapter.KEY).configured()).isTrue();
            var g=grant(p,2,2*R);
            var created=creation.create(p,"read-history",input(facts,g));
            // 使用已实现的幂等历史读取；Task 8 的列表/lookup 不属于此装配测试。
            assertThat(creation.create(p,"read-history",input(facts,g)).value().request().id()).isEqualTo(created.value().request().id());
            assertThat(callbacks).containsExactly(created.value().request().id());
            var s=controls.settings(p);
            controls.updateSettings(p,s.revision(),new DailySettingsInput("0.1.0",false,null));
            var previous=controls.getGrant(p,g); controls.revokeGrant(p,g); controls.revokeRisk(p,previous.riskAcknowledgementId());
            assertThat(controls.getGrant(p,g).revokedAt()).isNotNull();
            assertThat(controls.getRisk(p,previous.riskAcknowledgementId()).revokedAt()).isNotNull();
        }
        assertThat(consumes).hasValue(0); assertThat(initializes).hasValue(0); assertThat(adapter).hasNullValue();
    }
}
