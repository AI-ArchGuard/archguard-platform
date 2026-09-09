package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.project.internal.domain.Project;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalProjectCreator {
    private final ProjectRepository repository;
    private final AuditRecorder auditRecorder;

    public TransactionalProjectCreator(ProjectRepository repository, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    @Transactional
    public void create(Project project, String traceId) {
        repository.insertWithMaintainer(project);
        auditRecorder.record(new AuditEvent(
            project.createdBy(), project.id().value(), "project.create", AuditResult.SUCCESS, traceId,
            Map.of("projectKey", project.key().value())));
    }
}
