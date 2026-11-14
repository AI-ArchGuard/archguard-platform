package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.LiveCostPolicy;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** 两种模式借用同一有界队列；只有资源所有者负责关闭执行器及其惰性适配器。 */
final class PersonalLiveRuntimeResources implements AutoCloseable {
    private final PersonalLiveExecutor executor;
    private final LiveAdapterBundle adapters;

    PersonalLiveRuntimeResources(LiveAdapterBundle adapters) {
        this.adapters = adapters;
        this.executor = new PersonalLiveExecutor(adapters);
    }

    PersonalLiveExecutor executor() { return executor; }
    LiveAdapterBundle adapters() { return adapters; }
    void requireOpen() { executor.requireOpen(); }
    @Override public void close() { executor.close(); }

    /** 各模式传入独立策略；包装时不解析目标或取得凭据。 */
    LiveResponsePort responses(Runnable configured) {
        return new LiveResponsePort() {
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
    }

    LiveExecutionCoordinator.CredentialUse credentials(Runnable configured) {
        return new LiveExecutionCoordinator.CredentialUse() {
            @Override public LiveResponsePort.Reply use(UUID version, Runnable preflight,
                    Function<String, LiveResponsePort.Reply> send) {
                throw new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
            }
            @Override public LiveResponsePort.Reply use(UUID version, LongSupplier remaining, Runnable preflight,
                    Function<String, LiveResponsePort.Reply> send) {
                configured.run(); return adapters.credentials().use(version, remaining, preflight, send);
            }
        };
    }
}
