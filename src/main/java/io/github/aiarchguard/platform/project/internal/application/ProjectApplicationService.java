package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.identity.CurrentActor;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectKeyConflictException;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectOperations;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import io.github.aiarchguard.platform.project.ProjectView;
import io.github.aiarchguard.platform.project.internal.domain.Project;
import io.github.aiarchguard.platform.project.internal.domain.ProjectKey;
import io.github.aiarchguard.platform.project.internal.domain.ProjectName;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
final class ProjectApplicationService implements ProjectOperations {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectApplicationService.class);
    private static final String CREATE_PERMISSION = "project:create";

    private final CurrentActorProvider actors;
    private final ProjectIdGenerator idGenerator;
    private final TransactionalProjectCreator creator;
    private final TransactionalProjectReader reader;
    private final AuditRecorder auditRecorder;
    private final TraceIdProvider traceIds;
    private final Clock clock;

    ProjectApplicationService(CurrentActorProvider actors, ProjectIdGenerator idGenerator,
                              TransactionalProjectCreator creator, TransactionalProjectReader reader,
                              AuditRecorder auditRecorder, TraceIdProvider traceIds, Clock clock) {
        this.actors = actors;
        this.idGenerator = idGenerator;
        this.creator = creator;
        this.reader = reader;
        this.auditRecorder = auditRecorder;
        this.traceIds = traceIds;
        this.clock = clock;
    }

    @Override
    public ProjectView create(String rawKey, String rawName) {
        CurrentActor actor = actors.currentActor();
        ProjectKey key = new ProjectKey(rawKey);
        if (!actor.hasPermission(CREATE_PERMISSION)) {
            recordFailure(actor.id(), null, "project.create", AuditResult.DENIED, Map.of("projectKey", key.value()));
            throw new ProjectPermissionDeniedException();
        }

        Project project = new Project(idGenerator.nextId(), key, new ProjectName(rawName), actor.id(),
            Instant.now(clock));
        try {
            creator.create(project, traceIds.currentTraceId());
            return toView(project);
        } catch (DuplicateKeyException exception) {
            recordFailure(actor.id(), null, "project.create", AuditResult.CONFLICT,
                Map.of("projectKey", key.value()));
            throw new ProjectKeyConflictException(key.value());
        }
    }

    @Override
    public ProjectView get(UUID projectId) {
        CurrentActor actor = actors.currentActor();
        return reader.findForActor(projectId, actor.id())
            .map(ProjectApplicationService::toView)
            .orElseThrow(() -> {
                recordFailure(actor.id(), projectId, "project.read", AuditResult.DENIED, Map.of());
                return new ProjectNotFoundException();
            });
    }

    private void recordFailure(UUID actorId, UUID projectId, String action, AuditResult result,
                               Map<String, Object> metadata) {
        try {
            auditRecorder.record(new AuditEvent(
                actorId, projectId, action, result, traceIds.currentTraceId(), metadata));
        } catch (RuntimeException exception) {
            LOGGER.error("event=audit_record_failed action={} result={} exception={}",
                action, result, exception.getClass().getName());
        }
    }

    private static ProjectView toView(Project project) {
        return new ProjectView(project.id().value(), project.key().value(), project.name().value(),
            project.createdBy(), project.createdAt());
    }
}
