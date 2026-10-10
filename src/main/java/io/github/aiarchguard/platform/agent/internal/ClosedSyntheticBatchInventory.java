package io.github.aiarchguard.platform.agent.internal;

import java.util.Optional;
import java.util.UUID;

/** No production inventory enrollment or live authorization in this slice. */
final class ClosedSyntheticBatchInventory implements SyntheticBatchInventory {
    @Override public Optional<Proof> resolve(UUID project, UUID inventory, String digest) { return Optional.empty(); }
}
