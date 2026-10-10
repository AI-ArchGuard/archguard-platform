package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LiveBatchInput(UUID enablementId, UUID syntheticInventoryId, List<UUID> templateRequestIds,
        String manifestSha256, Instant expiresAt, int maxRequests, long maxCostMicrousd, boolean callsApproved) {
    public LiveBatchInput { templateRequestIds = templateRequestIds == null ? null : List.copyOf(templateRequestIds); }
}
