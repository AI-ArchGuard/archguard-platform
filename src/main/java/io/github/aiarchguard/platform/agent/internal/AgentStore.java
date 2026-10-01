package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.AgentRequestView;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface AgentStore {
    boolean insert(AgentSnapshot snapshot, String idempotencyKey);
    Optional<AgentSnapshot> findByKey(UUID projectId, UUID requesterId, String idempotencyKey);
    Optional<AgentSnapshot> find(UUID projectId, UUID requestId);
    boolean claim(UUID requestId, Instant at);
    void setReservation(UUID requestId, java.time.LocalDate day, long estimatedMicrousd);
    boolean failQueued(UUID requestId, AgentRequestView.AgentFailure failure, Instant at);
    boolean failRunning(UUID requestId, AgentRequestView.AgentFailure failure, Instant at);
    boolean finish(UUID requestId, AgentRequestView.AgentResult result, AgentRequestView.AgentFailure failure,
            AgentRequestView.AgentUsage usage, Instant at);
    boolean hasRequests(UUID projectId);
    List<AgentSnapshot> expired(Instant cutoff, int limit);
    boolean expire(UUID requestId, Instant cutoff, AgentRequestView.AgentFailure failure, Instant at);
}
