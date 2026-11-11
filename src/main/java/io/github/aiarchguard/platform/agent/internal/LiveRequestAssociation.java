package io.github.aiarchguard.platform.agent.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
/** 模式适配器不复制发送和调度算法。 */
interface LiveRequestAssociation {
 Optional<LiveRequestBinding> find(UUID projectId, UUID requestId);
 boolean claim(LiveRequestBinding exactBinding, UUID actorId, Instant at);
 void requireCurrent(LiveRequestBinding binding, AgentSnapshot snapshot, LongSupplier remaining);
 LiveExecutionLease lease(LiveRequestBinding binding);
 void stop(LiveRequestBinding binding, String code);
}
