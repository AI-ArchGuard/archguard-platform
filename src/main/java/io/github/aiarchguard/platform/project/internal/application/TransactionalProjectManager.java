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

/**
 * 项目与成员变更的应用事务服务；每个公开方法把授权、业务写入和成功审计放在同一事务。
 * 外层应用服务调用此独立 Bean，事务代理负责提交或回滚，随后外层再处理已捕获的失败审计。
 * 各写用例先锁项目行，以此作为同一项目成员治理的串行点；名称修改和删除还要求版本匹配。
 */
@Service
public class TransactionalProjectManager {
    private final ProjectRepository repository;
    private final AuditRecorder auditRecorder;

    public TransactionalProjectManager(ProjectRepository repository, AuditRecorder auditRecorder) {
        this.repository = repository;
        this.auditRecorder = auditRecorder;
    }

    /** 维护者按期望版本改名，返回保留原身份与创建信息、版本加一的新领域对象。 */
    @Transactional
    public Project update(UUID projectId, UUID actorId, ProjectName name, long expectedVersion, String traceId) {
        ProjectAccess access = requireMaintainer(projectId, actorId);
        // 既检查读到的版本，也要求 SQL 按同一版本命中一行，避免接受客户端的过期修改。
        if (access.project().version() != expectedVersion
            || repository.updateName(projectId, name, expectedVersion) != 1) {
            throw new ProjectVersionConflictException();
        }
        Project updated = new Project(access.project().id(), access.project().key(), name,
            access.project().createdBy(), access.project().createdAt(), expectedVersion + 1);
        record(actorId, projectId, "project.update", traceId, Map.of("version", updated.version()));
        return updated;
    }

    /**
     * 在持有项目行锁且确认维护者权限后，按版本物理删除项目并写成功审计。
     * 跨模块内容检查由外层在进入本事务之前执行，本方法没有把它与删除原子化。
     */
    @Transactional
    public void delete(UUID projectId, UUID actorId, long expectedVersion, String traceId) {
        ProjectAccess access = requireMaintainer(projectId, actorId);
        if (access.project().version() != expectedVersion
            || repository.delete(projectId, expectedVersion) != 1) {
            throw new ProjectVersionConflictException();
        }
        record(actorId, projectId, "project.delete", traceId, Map.of("version", expectedVersion));
    }

    /**
     * 维护者新增成员或替换角色；已有维护者被降为 Viewer 前，必须确认还有另一位维护者。
     * 写入后重新查询，使返回值保留已有成员最初的加入时间，而非总使用本次传入时间。
     */
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

    /** 维护者移除指定成员；不存在返回成员错误，移除最后一位维护者返回不变量冲突。 */
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

    /**
     * 在调用方已开启的写事务中锁项目行，并按成员角色授权。
     * 查询为空时用项目不存在隐藏非成员访问；已可见但只有 Viewer 角色时才报告权限不足。
     */
    private ProjectAccess requireMaintainer(UUID projectId, UUID actorId) {
        ProjectAccess access = repository.lockForActor(projectId, actorId)
            .orElseThrow(ProjectNotFoundException::new);
        if (access.role() != ProjectRole.MAINTAINER) {
            throw new ProjectPermissionDeniedException();
        }
        return access;
    }

    /**
     * 调用时项目行锁仍由当前事务持有，遵循同一锁约定的成员变更不会同时通过这次计数。
     * 单独调用 countMaintainers 并不能保护“至少一名维护者”，保护依赖计数与变更共用事务和锁。
     */
    private void ensureAnotherMaintainer(UUID projectId) {
        if (repository.countMaintainers(projectId) <= 1) {
            throw new LastProjectMaintainerException();
        }
    }

    /** 成功审计参与当前业务事务；写入异常继续抛出，使业务写入也回滚。 */
    private void record(UUID actorId, UUID projectId, String action, String traceId,
                        Map<String, Object> metadata) {
        auditRecorder.record(new AuditEvent(
            actorId, projectId, action, AuditResult.SUCCESS, traceId, metadata));
    }
}
