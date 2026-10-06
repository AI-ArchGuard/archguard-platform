package io.github.aiarchguard.platform.agent;

import java.time.Instant;
import java.util.UUID;

/** Deliberately excludes account refs, source material and operations-check references. */
public record PersonalEnablementView(UUID id, UUID projectId, UUID approvedBy, Instant approvedAt,
        Instant expiresAt, UUID credentialVersion, String modelAlias, String priceCatalogVersion,
        Instant priceCatalogExpiresAt, String dataScope, boolean revoked, Instant revokedAt) { }
