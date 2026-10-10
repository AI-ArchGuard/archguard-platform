package io.github.aiarchguard.platform.agent.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Trusted deployment provenance boundary. Caller text/checkboxes cannot implement this proof. */
public interface SyntheticBatchInventory {
    Optional<Proof> resolve(UUID projectId, UUID inventoryId, String manifestSha256);
    default boolean accepts(UUID projectId, UUID inventoryId, String manifestSha256) {
        return resolve(projectId, inventoryId, manifestSha256).isPresent();
    }
    record Proof(String schemaVersion, String fileSha256, String fixtureSetVersion, String fixtureArtifactSha256,
            String reviewRef, Instant reviewedAt, Instant expiresAt) { }
}
