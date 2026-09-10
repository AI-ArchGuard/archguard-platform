package io.github.aiarchguard.platform.audit;

public interface AuditRecorder {
    void record(AuditEvent event);
}
