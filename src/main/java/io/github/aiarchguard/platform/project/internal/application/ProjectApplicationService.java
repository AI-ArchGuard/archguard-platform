package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.identity.CurrentActor;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.InvalidProjectException;
import io.github.aiarchguard.platform.project.LastProjectMaintainerException;
import io.github.aiarchguard.platform.project.ProjectKeyConflictException;
import io.github.aiarchguard.platform.project.ProjectMemberNotFoundException;
import io.github.aiarchguard.platform.project.ProjectMemberPage;
import io.github.aiarchguard.platform.project.ProjectMemberRole;
import io.github.aiarchguard.platform.project.ProjectMemberView;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectOperations;
import io.github.aiarchguard.platform.project.ProjectPage;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import io.github.aiarchguard.platform.project.ProjectVersionConflictException;
import io.github.aiarchguard.platform.project.ProjectView;
import io.github.aiarchguard.platform.project.internal.domain.Project;
import io.github.aiarchguard.platform.project.internal.domain.ProjectKey;
import io.github.aiarchguard.platform.project.internal.domain.ProjectMember;
import io.github.aiarchguard.platform.project.internal.domain.ProjectName;
import io.github.aiarchguard.platform.project.internal.domain.ProjectRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    private final TransactionalProjectManager manager;
    private final AuditRecorder auditRecorder;
    private final TraceIdProvider traceIds;
    private final Clock clock;

    ProjectApplicationService(CurrentActorProvider actors, ProjectIdGenerator idGenerator,
                              TransactionalProjectCreator creator, TransactionalProjectReader reader,
                              TransactionalProjectManager manager, AuditRecorder auditRecorder,
                              TraceIdProvider traceIds, Clock clock) {
        this.actors = actors;
        this.idGenerator = idGenerator;
        this.creator = creator;
        this.reader = reader;
        this.manager = manager;
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
            Instant.now(clock), 0);
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

    @Override
    public ProjectPage list(int page, int size) {
        validatePage(page, size);
        CurrentActor actor = actors.currentActor();
        ProjectPageData data = reader.listForActor(actor.id(), page, size);
        return new ProjectPage(data.items().stream().map(ProjectApplicationService::toView).toList(),
            page, size, data.total());
    }

    @Override
    public ProjectView update(UUID projectId, String rawName, long expectedVersion) {
        validateVersion(expectedVersion);
        CurrentActor actor = actors.currentActor();
        try {
            return toView(manager.update(projectId, actor.id(), new ProjectName(rawName), expectedVersion,
                traceIds.currentTraceId()));
        } catch (ProjectNotFoundException | ProjectPermissionDeniedException exception) {
            recordFailure(actor.id(), projectId, "project.update", AuditResult.DENIED, Map.of());
            throw exception;
        } catch (ProjectVersionConflictException exception) {
            recordFailure(actor.id(), projectId, "project.update", AuditResult.CONFLICT,
                Map.of("version", expectedVersion));
            throw exception;
        }
    }

    @Override
    public void delete(UUID projectId, long expectedVersion) {
        validateVersion(expectedVersion);
        CurrentActor actor = actors.currentActor();
        try {
            manager.delete(projectId, actor.id(), expectedVersion, traceIds.currentTraceId());
        } catch (ProjectNotFoundException | ProjectPermissionDeniedException exception) {
            recordFailure(actor.id(), projectId, "project.delete", AuditResult.DENIED, Map.of());
            throw exception;
        } catch (ProjectVersionConflictException exception) {
            recordFailure(actor.id(), projectId, "project.delete", AuditResult.CONFLICT,
                Map.of("version", expectedVersion));
            throw exception;
        }
    }

    @Override
    public ProjectMemberPage listMembers(UUID projectId, int page, int size) {
        validatePage(page, size);
        CurrentActor actor = actors.currentActor();
        ProjectMemberPageData data = reader.listMembersForActor(projectId, actor.id(), page, size)
            .orElseThrow(() -> {
                recordFailure(actor.id(), projectId, "project.members.read", AuditResult.DENIED, Map.of());
                return new ProjectNotFoundException();
            });
        List<ProjectMemberView> items = data.items().stream()
            .map(ProjectApplicationService::toMemberView)
            .toList();
        return new ProjectMemberPage(items, page, size, data.total());
    }

    @Override
    public ProjectMemberView setMember(UUID projectId, UUID memberActorId, ProjectMemberRole role) {
        validateMember(memberActorId, role);
        CurrentActor actor = actors.currentActor();
        try {
            ProjectMember member = manager.setMember(projectId, actor.id(), memberActorId,
                ProjectRole.valueOf(role.name()), Instant.now(clock), traceIds.currentTraceId());
            return toMemberView(member);
        } catch (ProjectNotFoundException | ProjectPermissionDeniedException exception) {
            recordFailure(actor.id(), projectId, "project.member.set", AuditResult.DENIED,
                Map.of("memberActorId", memberActorId.toString()));
            throw exception;
        } catch (LastProjectMaintainerException exception) {
            recordFailure(actor.id(), projectId, "project.member.set", AuditResult.CONFLICT,
                Map.of("memberActorId", memberActorId.toString()));
            throw exception;
        }
    }

    @Override
    public void removeMember(UUID projectId, UUID memberActorId) {
        validateMemberActorId(memberActorId);
        CurrentActor actor = actors.currentActor();
        try {
            manager.removeMember(projectId, actor.id(), memberActorId, traceIds.currentTraceId());
        } catch (ProjectNotFoundException | ProjectPermissionDeniedException exception) {
            recordFailure(actor.id(), projectId, "project.member.remove", AuditResult.DENIED,
                Map.of("memberActorId", memberActorId.toString()));
            throw exception;
        } catch (LastProjectMaintainerException | ProjectMemberNotFoundException exception) {
            recordFailure(actor.id(), projectId, "project.member.remove", AuditResult.CONFLICT,
                Map.of("memberActorId", memberActorId.toString()));
            throw exception;
        }
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
            project.createdBy(), project.createdAt(), project.version());
    }

    private static ProjectMemberView toMemberView(ProjectMember member) {
        return new ProjectMemberView(member.actorId(), ProjectMemberRole.valueOf(member.role().name()),
            member.createdAt());
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidProjectException("Page must be non-negative and size must be between 1 and 100");
        }
    }

    private static void validateVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new InvalidProjectException("Project version must not be negative");
        }
    }

    private static void validateMember(UUID memberActorId, ProjectMemberRole role) {
        validateMemberActorId(memberActorId);
        if (role == null) {
            throw new InvalidProjectException("Member actor and role are required");
        }
    }

    private static void validateMemberActorId(UUID memberActorId) {
        if (memberActorId == null) {
            throw new InvalidProjectException("Member actor is required");
        }
    }
}
