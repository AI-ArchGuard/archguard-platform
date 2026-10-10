package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.UUID;

public record LiveBatchView(UUID id, UUID projectId, UUID enablementId, UUID approvedBy, Instant approvedAt,
        String manifestSha256, Instant expiresAt, int maxRequests, long maxCostMicrousd, boolean revoked) { }
