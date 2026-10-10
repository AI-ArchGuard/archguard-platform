package io.github.aiarchguard.platform.agent.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** No production inventory enrollment or live authorization in this slice. */
@Component
final class ClosedSyntheticBatchInventory implements SyntheticBatchInventory {
    @Override public boolean accepts(UUID project, UUID inventory, String digest) { return false; }
}
