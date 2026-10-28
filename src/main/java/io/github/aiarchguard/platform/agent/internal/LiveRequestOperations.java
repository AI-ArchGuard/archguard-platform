package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentRequestView;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 真实请求创建的内部端口；只原子保存请求及批次/模板关联，不消费凭据或发送网络，由上层显式调度，有界重载默认拒绝无界回退。
 */
public interface LiveRequestOperations {
    AgentRequestView create(UUID project, UUID batch, UUID template, String idempotencyKey,
        String origin, boolean secure);

    /** Bounded production creation must not silently invoke a legacy unbounded implementation. */
    default AgentRequestView create(UUID project, UUID batch, UUID template, String idempotencyKey,
            String origin, boolean secure, LongSupplier remainingNanos) {
        throw new io.github.aiarchguard.platform.agent.AgentUnavailableException();
    }
}
