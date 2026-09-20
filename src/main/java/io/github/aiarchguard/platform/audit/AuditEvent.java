package io.github.aiarchguard.platform.audit;

import java.util.Map;
import java.util.UUID;

public record AuditEvent(UUID actorId, UUID projectId, String action, AuditResult result,
                         String traceId, Map<String, Object> metadata) {
    public AuditEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
