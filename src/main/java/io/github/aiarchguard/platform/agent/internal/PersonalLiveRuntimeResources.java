package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;

/** 运行资源的接缝；共享生命周期接入之前，不能取得执行器或适配器。 */
final class PersonalLiveRuntimeResources implements AutoCloseable {
    PersonalLiveRuntimeResources(LiveAdapterBundle adapters) { }
    PersonalLiveExecutor executor() { throw new AgentUnavailableException(); }
    LiveAdapterBundle adapters() { throw new AgentUnavailableException(); }
    void requireOpen() { throw new AgentUnavailableException(); }
    public void close() { }
}
