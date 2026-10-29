package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 批准批次时提交的来源清单、模板摘要、期限及次数费用限制；服务端还须核验确认与受控来源证明。 */
public record LiveBatchInput(UUID enablementId, UUID syntheticInventoryId, List<UUID> templateRequestIds,
        String manifestSha256, Instant expiresAt, int maxRequests, long maxCostMicrousd, boolean callsApproved) {
    public LiveBatchInput { templateRequestIds = templateRequestIds == null ? null : List.copyOf(templateRequestIds); }
}
