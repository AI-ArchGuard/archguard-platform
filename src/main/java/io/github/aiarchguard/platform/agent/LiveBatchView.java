package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.UUID;

/** 批次批准元数据的公开视图；记录批准人、期限、上限和撤销状态，不暴露模型输入或凭据。 */
public record LiveBatchView(UUID id, UUID projectId, UUID enablementId, UUID approvedBy, Instant approvedAt,
        String manifestSha256, Instant expiresAt, int maxRequests, long maxCostMicrousd, boolean revoked) { }
