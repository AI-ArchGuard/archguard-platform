package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.project.LastProjectMaintainerException;
import io.github.aiarchguard.platform.project.ProjectMemberNotFoundException;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectPermissionDeniedException;
import io.github.aiarchguard.platform.project.ProjectVersionConflictException;
import io.github.aiarchguard.platform.project.internal.domain.Project;
import io.github.aiarchguard.platform.project.internal.domain.ProjectMember;
import io.github.aiarchguard.platform.project.internal.domain.ProjectName;
import io.github.aiarchguard.platform.project.internal.domain.ProjectRole;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionalProjectManager {
    private final ProjectRepository repository;
    private final AuditRecorder auditRecorder;

    public TransactionalProjectManager(ProjectRepository repository, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    @Transactional
    public Project update(UUID projectId, UUID actorId, ProjectName name, long expectedVersion, String traceId) {
        ProjectAccess access = requireMaintainer(projectId, actorId);
        if (access.project().version() != expectedVersion
            || repository.updateName(projectId, name, expectedVersion) != 1) {
            throw new ProjectVersionConflictException();
        }
        Project updated = new Project(access.project().id(), access.project().key(), name,
            access.project().createdBy(), access.project().createdAt(), expectedVersion + 1);
        record(actorId, projectId, "project.update", traceId, Map.of("version", updated.version()));
        return updated;
    }

    @Transactional
    public void delete(UUID projectId, UUID actorId, long expectedVersion, String traceId) {
        ProjectAccess access = requireMaintainer(projectId, actorId);
        if (access.project().version() != expectedVersion
            || repository.delete(projectId, expectedVersion) != 1) {
            throw new ProjectVersionConflictException();
        }
        record(actorId, projectId, "project.delete", traceId, Map.of("version", expectedVersion));
    }

    @Transactional
    public ProjectMember setMember(UUID projectId, UUID actorId, UUID memberActorId, ProjectRole role,
                                   Instant createdAt, String traceId) {
        requireMaintainer(projectId, actorId);
        repository.findMember(projectId, memberActorId).ifPresent(existing -> {
            if (existing.role() == ProjectRole.MAINTAINER && role == ProjectRole.VIEWER) {
                ensureAnotherMaintainer(projectId);
            }
        });
        repository.upsertMember(projectId, memberActorId, role, createdAt);
        ProjectMember member = repository.findMember(projectId, memberActorId).orElseThrow();
        record(actorId, projectId, "project.member.set", traceId,
            Map.of("memberActorId", memberActorId.toString(), "role", role.name()));
        return member;
    }

    @Transactional
    public void removeMember(UUID projectId, UUID actorId, UUID memberActorId, String traceId) {
        requireMaintainer(projectId, actorId);
        ProjectMember member = repository.findMember(projectId, memberActorId)
            .orElseThrow(ProjectMemberNotFoundException::new);
        if (member.role() == ProjectRole.MAINTAINER) {
            ensureAnotherMaintainer(projectId);
        }
        if (repository.deleteMember(projectId, memberActorId) != 1) {
            throw new ProjectMemberNotFoundException();
        }
        record(actorId, projectId, "project.member.remove", traceId,
            Map.of("memberActorId", memberActorId.toString(), "role", member.role().name()));
    }

    private ProjectAccess requireMaintainer(UUID projectId, UUID actorId) {
        ProjectAccess access = repository.lockForActor(projectId, actorId)
            .orElseThrow(ProjectNotFoundException::new);
        if (access.role() != ProjectRole.MAINTAINER) {
            throw new ProjectPermissionDeniedException();
        }
        return access;
    }

    private void ensureAnotherMaintainer(UUID projectId) {
        if (repository.countMaintainers(projectId) <= 1) {
            throw new LastProjectMaintainerException();
        }
    }

    private void record(UUID actorId, UUID projectId, String action, String traceId,
                        Map<String, Object> metadata) {
        auditRecorder.record(new AuditEvent(
            actorId, projectId, action, AuditResult.SUCCESS, traceId, metadata));
    }
}
