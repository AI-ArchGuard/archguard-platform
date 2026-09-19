package io.github.aiarchguard.platform.finding;

import java.time.Instant;
import java.util.UUID;

public record DispositionView(UUID id, UUID findingId, FindingDisposition previousDisposition,
                              FindingDisposition newDisposition, String reason, UUID actorId,
                              Instant occurredAt, long version) {
}
