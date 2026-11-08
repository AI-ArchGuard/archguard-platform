package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentUnavailableException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
/** RED 接缝：完整失败证据被观察前，全部新能力拒绝。 */
final class SyntheticLiveRequestAssociation implements LiveRequestAssociation {
 SyntheticLiveRequestAssociation(LiveRequestStore store, LiveAccountingOperations accounting) { }
 public Optional<LiveRequestBinding> find(UUID project, UUID request) { throw new AgentUnavailableException(); }
 public boolean claim(LiveRequestBinding binding, UUID actor, Instant at) { throw new AgentUnavailableException(); }
 public void requireCurrent(LiveRequestBinding binding, AgentSnapshot snapshot, LongSupplier remaining) { throw new AgentUnavailableException(); }
 public LiveExecutionLease lease(LiveRequestBinding binding) { throw new AgentUnavailableException(); }
 public void stop(LiveRequestBinding binding, String code) { throw new AgentUnavailableException(); }
}
