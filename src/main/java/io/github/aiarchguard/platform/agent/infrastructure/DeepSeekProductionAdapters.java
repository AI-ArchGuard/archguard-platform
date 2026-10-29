package io.github.aiarchguard.platform.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.internal.LiveAdapterBundle;
import io.github.aiarchguard.platform.agent.internal.LiveExecutionCoordinator;
import io.github.aiarchguard.platform.agent.internal.LiveResponsePort;
import io.github.aiarchguard.platform.agentcredential.CredentialUseSource;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Spring 中注册的惰性生产适配器集合；
 * 构造不读取 Key、不创建 HTTP 或探测网络，实际使用时才初始化固定协议资源，关闭后不能重建。
 */
@Component
final class DeepSeekProductionAdapters implements LiveAdapterBundle {
    private final ObjectMapper mapper;
    private final VersionBoundCredentialUse credentials;
    private State state;
    private boolean closed;
    private final LiveResponsePort lazyResponses = new LiveResponsePort() {
        @Override public byte[] prepare(AgentModelPort.ModelInput input) { return client().prepare(input); }
        @Override public Reply send(byte[] body, String key, Duration remaining, Set<String> models) {
            return client().send(body, key, remaining, models);
        }
        @Override public Reply send(byte[] body, String key, Duration remaining, Set<String> models, String price) {
            return client().send(body, key, remaining, models, price);
        }
    };

    DeepSeekProductionAdapters(ObjectMapper mapper, CredentialUseSource source) {
        this.mapper = mapper; this.credentials = new VersionBoundCredentialUse(source);
    }
    @Override public LiveResponsePort responses() { return lazyResponses; }
    @Override public LiveExecutionCoordinator.CredentialUse credentials() { return credentials; }

    /** 首次实际使用才创建固定 HTTP/DNS 资源；同步保护关闭与初始化竞争，初始化失败先关闭局部资源。 */
    private synchronized DeepSeekLiveResponseClient client() {
        if (closed) throw new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE");
        if (state == null) {
            var exchange = new PublicDeepSeekExchange();
            try { state = new State(exchange, new DeepSeekLiveResponseClient(mapper, exchange)); }
            catch (RuntimeException unavailable) { exchange.close(); throw new LiveResponsePort.CallFailure("MODEL_UNAVAILABLE"); }
        }
        return state.client();
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (state != null) state.exchange().close();
    }
    @Override public boolean awaitTermination(Duration timeout) throws InterruptedException {
        State active;
        synchronized (this) { active = state; }
        return active == null || active.exchange().awaitTermination(timeout);
    }
    private record State(PublicDeepSeekExchange exchange, DeepSeekLiveResponseClient client) { }
}
