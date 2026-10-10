package io.github.aiarchguard.platform.agent.internal;

import java.util.UUID;

/** Trusted deployment provenance boundary. Caller text/checkboxes cannot implement this proof. */
public interface SyntheticBatchInventory {
    boolean accepts(UUID projectId, UUID inventoryId, String manifestSha256);
}
