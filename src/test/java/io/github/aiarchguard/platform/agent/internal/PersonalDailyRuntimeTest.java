package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.*;
import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/** 两个门面共享同一个有界执行器。替身只观察关闭、实际等待以及是否尝试构造适配器。 */
class PersonalDailyRuntimeTest {
    @Test void deploymentDefaultsHaveNoRuntimeFactsOrSpendingApproval() throws Exception {
        var yaml=new YamlPropertySourceLoader().load("defaults",new ClassPathResource("application.yml")).getFirst();
        assertThat(yaml.getProperty("archguard.agent.daily.enabled")).isIn(false,"false","${ARCHGUARD_AGENT_DAILY_ENABLED:false}");
        for(String name:new String[]{"runtime-revision","secret-check-ref","network-check-ref","max-attempts","max-cost-microusd","expires-at",
                "evidence.price-catalog-expires-at","evidence.sources[0].checked-at"}) {
            var value=yaml.getProperty("archguard.agent.daily."+name);
            if(value!=null) assertThat(value.toString()).matches("(|\\$\\{[A-Z0-9_]+:\\})");
        }
    }
    @Test void borrowedFacadesShareOneWorkerQueueAndCloseExactlyOnce() throws Exception {
        var bundle=new PersonalLiveExecutorTest.FakeBundle(); var owner=new PersonalLiveRuntimeResources(bundle);
        var entered=new CountDownLatch(1); var exited=new CountDownLatch(1); var interrupted=new AtomicBoolean();
        var calls=new AtomicInteger();
        try {
            var shared=owner.executor();
            assertThat(owner.executor()).isSameAs(shared); assertThat(owner.adapters()).isSameAs(bundle);
            var dispatcher=new LiveRequestDispatcher(null,null,null,null,null,null,(p,b,t,id)->{
                throw new AssertionError("不应进入模型核心");
            },shared,()->{throw new AssertionError("关闭后不得调度");});
            var legacy=new PersonalLiveRuntime(owner,dispatcher,()->{});
            var daily=new PersonalLiveRuntime(owner,dispatcher,()->{});
            legacy.close(); daily.close(); assertThat(bundle.closes).hasValue(0);
            legacy.requireAvailable(); daily.requireAvailable();
            shared.execute(()->{
                entered.countDown();
                try { new CountDownLatch(1).await(10,TimeUnit.SECONDS); }
                catch(InterruptedException expected) { interrupted.set(true); }
                finally { exited.countDown(); }
            });
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            owner.executor().execute(calls::incrementAndGet); owner.executor().execute(calls::incrementAndGet);
            assertThatThrownBy(()->shared.execute(calls::incrementAndGet)).isInstanceOf(AgentUnavailableException.class);
            owner.close(); owner.close();
            assertThat(exited.getCount()).isZero(); assertThat(interrupted).isTrue(); assertThat(calls).hasValue(0);
            assertThat(bundle.closes).hasValue(1); assertThat(bundle.awaited).isTrue();
            assertThat(shared.awaitTermination(Duration.ofSeconds(1))).isTrue();
            for(var facade:new PersonalLiveRuntime[]{legacy,daily})
                assertThatThrownBy(()->facade.dispatch(UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(AgentUnavailableException.class);
            assertThatThrownBy(owner::requireOpen).isInstanceOf(AgentUnavailableException.class);
            assertThat(bundle.responses).hasValue(0); assertThat(bundle.credentials).hasValue(0);
        } finally { owner.close(); }
    }
    @Test void standaloneLegacyFacadeStillOwnsItsResources() {
        var bundle=new PersonalLiveExecutorTest.FakeBundle(); var executor=new PersonalLiveExecutor(bundle);
        var dispatcher=new LiveRequestDispatcher(null,null,null,null,null,null,(p,b,t,id)->{},executor,()->{});
        var legacy=new PersonalLiveRuntime(executor,dispatcher,()->{});
        legacy.close(); assertThat(executor.isOpen()).isFalse(); assertThat(bundle.closes).hasValue(1);
        assertThat(bundle.responses).hasValue(0); assertThat(bundle.credentials).hasValue(0);
    }
}
