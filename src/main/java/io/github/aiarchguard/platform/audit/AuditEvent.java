package io.github.aiarchguard.platform.audit;

import java.util.Map;
import java.util.UUID;

/** 模块提交给审计端口的事件快照；metadata 由调用方最小化，不能包含凭据或完整源码。 */
public record AuditEvent(UUID actorId, UUID projectId, String action, AuditResult result,
                         String traceId, Map<String, Object> metadata) {
    public AuditEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
