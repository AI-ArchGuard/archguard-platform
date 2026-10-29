package io.github.aiarchguard.platform.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证默认关闭的个人运行时在调度、排队和适配器初始化前拒绝请求。此单元测试只检查拒绝顺序与零副作用。
 */
class PersonalLiveRuntimeTest {
    // 默认关闭必须覆盖执行链最前端，不能先产生工作再返回不可用。
    @Test void defaultClosedRuntimeRejectsBeforeDispatchQueueOrAdapterInitialization() {
        var bundle = new PersonalLiveExecutorTest.FakeBundle(); var executor = new PersonalLiveExecutor(bundle);
        var dispatcherEntries = new AtomicInteger();
        var dispatcher = new LiveRequestDispatcher(null, null, null, null, null, null,
            (p, b, t, r) -> { throw new AssertionError("Default-closed runtime cannot execute"); }, executor,
            () -> { dispatcherEntries.incrementAndGet(); throw new AssertionError("Must reject before dispatcher"); });
        var runtime = new PersonalLiveRuntime(executor, dispatcher, () -> { throw new AgentUnavailableException(); });
        try {
            assertThatThrownBy(() -> runtime.dispatch(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(AgentUnavailableException.class).hasNoCause();
            assertThat(dispatcherEntries).hasValue(0);
            assertThat(bundle.responses).hasValue(0); assertThat(bundle.credentials).hasValue(0);
        } finally { executor.close(); }
    }
}
