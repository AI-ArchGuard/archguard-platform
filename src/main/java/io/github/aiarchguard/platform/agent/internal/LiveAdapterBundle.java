package io.github.aiarchguard.platform.agent.internal;

import java.time.Duration;

/** 基础设施拥有的惰性真实协议资源集合；只暴露响应和版本绑定凭据回调，生命周期关闭还需确认线程实际终止。 */
public interface LiveAdapterBundle extends AutoCloseable {
    LiveResponsePort responses();
    LiveExecutionCoordinator.CredentialUse credentials();
    @Override void close();
    boolean awaitTermination(Duration timeout) throws InterruptedException;
}
