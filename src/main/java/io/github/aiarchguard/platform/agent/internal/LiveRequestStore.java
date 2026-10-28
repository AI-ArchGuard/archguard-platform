package io.github.aiarchguard.platform.agent.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 不可变请求与批次、模板、确认、凭据和设置修订号的关联端口；精确关联领取与费用尝试分开，claim 本身不预留模型费用。
 */
public interface LiveRequestStore {
    record Link(UUID requestId, UUID projectId, UUID batchId, UUID templateId, UUID enablementId,
            UUID credentialVersion, long settingsRevision, Instant createdAt) { }
    void insert(Link link);
    Optional<Link> find(UUID projectId, UUID requestId);
    /** Claim only the exact immutable association and live request projection; never reserves model cost. */
    default boolean claim(Link link, UUID requester, Instant at) { return false; }
}
