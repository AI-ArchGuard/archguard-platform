package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.UUID;

/** 个人确认的脱敏只读视图；有意省略账户引用、来源材料与运维检查引用，保留可追溯版本和撤销事实。 */
public record PersonalEnablementView(UUID id, UUID projectId, UUID approvedBy, Instant approvedAt,
        Instant expiresAt, UUID credentialVersion, String modelAlias, String priceCatalogVersion,
        Instant priceCatalogExpiresAt, String dataScope, boolean revoked, Instant revokedAt) { }
