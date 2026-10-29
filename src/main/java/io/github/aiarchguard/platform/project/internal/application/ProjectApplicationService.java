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
import io.github.aiarchguard.platform.project.ProjectAccessView;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.project.ProjectMemberNotFoundException;
import io.github.aiarchguard.platform.project.ProjectMemberPage;
import io.github.aiarchguard.platform.project.ProjectMemberRole;
import io.github.aiarchguard.platform.project.ProjectMemberView;
import io.github.aiarchguard.platform.project.ProjectNotFoundException;
import io.github.aiarchguard.platform.project.ProjectNotEmptyException;
import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
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

/**
 * Project 公开用例与授权接口的内部实现：取得可信身份、校验输入、编排事务服务并返回公开视图。
 * 本类不声明事务，创建、读取和变更分别调用独立 Bean，确保这些调用能经过 Spring 事务代理。
 * 成功审计在写事务内完成；这里仅对明确捕获的拒绝或冲突尽力补记失败审计。
 */
@Service
final class ProjectApplicationService implements ProjectOperations, ProjectAuthorization {
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
    private final List<ProjectDeletionGuard> deletionGuards;

    ProjectApplicationService(CurrentActorProvider actors, ProjectIdGenerator idGenerator,
                              TransactionalProjectCreator creator, TransactionalProjectReader reader,
                              TransactionalProjectManager manager, AuditRecorder auditRecorder,
                              TraceIdProvider traceIds, Clock clock, List<ProjectDeletionGuard> deletionGuards) {
        this.actors = actors;
        this.idGenerator = idGenerator;
        this.creator = creator;
        this.reader = reader;
        this.manager = manager;
        this.auditRecorder = auditRecorder;
        this.traceIds = traceIds;
        this.clock = clock;
        this.deletionGuards = List.copyOf(deletionGuards);
    }

    /**
     * 具有全局 project:create 权限的操作者创建项目，创建者同时成为首位维护者。
     * key 的格式校验和 name 的规范化由领域值对象负责；标识与时间在应用层注入，避免领域对象读取外部状态。
     */
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
            // 创建事务的异常先越过代理再到达这里，正常请求路径不会在已失败的创建事务内补写审计。
            recordFailure(actor.id(), null, "project.create", AuditResult.CONFLICT,
                Map.of("projectKey", key.value()));
            throw new ProjectKeyConflictException(key.value());
        }
    }

    /** 成员读取单个项目；不存在与非成员访问统一返回项目不存在，并尝试记录读取拒绝。 */
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

    /** 校验零基分页参数，只列出当前操作者作为成员可见的项目，并转换为公开分页视图。 */
    @Override
    public ProjectPage list(int page, int size) {
        validatePage(page, size);
        CurrentActor actor = actors.currentActor();
        ProjectPageData data = reader.listForActor(actor.id(), page, size);
        return new ProjectPage(data.items().stream().map(ProjectApplicationService::toView).toList(),
            page, size, data.total());
    }

    /** 以当前可信身份检查项目可读性；MAINTAINER 和 VIEWER 都满足此入口。 */
    @Override
    public ProjectAccessView requireViewer(UUID projectId) {
        CurrentActor actor = actors.currentActor();
        return requireViewerForActor(projectId, actor.id());
    }

    /**
     * 为可信内部调用方按明确的 actorId 复查成员资格，例如后台处理已保存的请求者身份。
     * 此方法只查成员关系，不认证这个 UUID 的来源；调用方必须提供可信身份，不能直接接受客户端自报值。
     * 不存在或非成员都抛项目不存在，且此授权辅助入口本身不补写失败审计。
     */
    @Override
    public ProjectAccessView requireViewerForActor(UUID projectId, UUID actorId) {
        ProjectAccess access = reader.findAccessForActor(projectId, actorId)
            .orElseThrow(ProjectNotFoundException::new);
        return toAccessView(access);
    }

    /** 先保证项目对当前操作者可见，再要求维护者角色；可见的 Viewer 权限不足时返回拒绝。 */
    @Override
    public ProjectAccessView requireMaintainer(UUID projectId) {
        ProjectAccessView access = requireViewer(projectId);
        if (access.role() != ProjectMemberRole.MAINTAINER) {
            throw new ProjectPermissionDeniedException();
        }
        return access;
    }

    /** 按客户端持有的项目版本改名，由事务服务复核维护者权限并处理版本竞争。 */
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

    /**
     * 先授权并询问各模块的删除保护端口，再交由事务服务重新授权、按版本删除。
     * 内容检查位于删除事务之前，不与删除共用项目行锁，不能据此宣称跨模块检查和删除是原子的。
     */
    @Override
    public void delete(UUID projectId, long expectedVersion) {
        validateVersion(expectedVersion);
        CurrentActor actor = actors.currentActor();
        try {
            requireMaintainer(projectId);
            if (deletionGuards.stream().anyMatch(guard -> guard.hasContent(projectId))) {
                // 当前捕获分支不含 ProjectNotEmptyException，因此内容阻止删除不会在这里补写失败审计。
                throw new ProjectNotEmptyException();
            }
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

    /** 任一项目成员均可查看成员页；读取服务先查可见性，外层统一处理隐藏项目的错误与失败审计。 */
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

    /**
     * 维护者设置目标成员角色；公开角色在此映射为内部领域角色。
     * 项目锁和最后维护者检查由事务服务执行，这里把授权拒绝或不变量冲突记录为失败尝试。
     */
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

    /**
     * 维护者移除目标成员，事务服务负责保护最后一位维护者。
     * 目标成员不存在与最后维护者冲突均按 CONFLICT 审计，但仍把原业务异常交给协议层映射。
     */
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

    /**
     * 尽力保存最小失败事实，审计失败只记日志，不替换原本的业务拒绝或冲突。
     * 正常请求中此调用发生在业务事务之外；这里使用普通 record，未显式声明 REQUIRES_NEW 新事务。
     * 因此不能把历史设计中的“独立短事务”理解为本方法已经提供的事务传播保证。
     */
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

    /** 在模块边界展开领域值对象，只把公开项目事实交给调用方。 */
    private static ProjectView toView(Project project) {
        return new ProjectView(project.id().value(), project.key().value(), project.name().value(),
            project.createdBy(), project.createdAt(), project.version());
    }

    /** 将领域角色转换为公开角色枚举，避免内部领域类型跨出模块。 */
    private static ProjectMemberView toMemberView(ProjectMember member) {
        return new ProjectMemberView(member.actorId(), ProjectMemberRole.valueOf(member.role().name()),
            member.createdAt());
    }

    /** 输出跨模块授权需要的项目摘要和角色，不暴露完整内部 ProjectAccess。 */
    private static ProjectAccessView toAccessView(ProjectAccess access) {
        Project project = access.project();
        return new ProjectAccessView(project.id().value(), project.key().value(), project.name().value(),
            ProjectMemberRole.valueOf(access.role().name()));
    }

    /** 应用接口也约束分页范围，避免绕过 Web 参数校验的内部调用发起无界读取。 */
    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidProjectException("Page must be non-negative and size must be between 1 and 100");
        }
    }

    /** 版本从零开始；具体是否过期仍需事务中的版本条件判断。 */
    private static void validateVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new InvalidProjectException("Project version must not be negative");
        }
    }

    /** 设置成员必须同时给出目标身份与角色，不把缺失角色解释为默认授权。 */
    private static void validateMember(UUID memberActorId, ProjectMemberRole role) {
        validateMemberActorId(memberActorId);
        if (role == null) {
            throw new InvalidProjectException("Member actor and role are required");
        }
    }

    /** 目标 UUID 只标识被管理成员，不代表当前调用者，也不在这里验证用户目录中的存在性。 */
    private static void validateMemberActorId(UUID memberActorId) {
        if (memberActorId == null) {
            throw new InvalidProjectException("Member actor is required");
        }
    }
}
