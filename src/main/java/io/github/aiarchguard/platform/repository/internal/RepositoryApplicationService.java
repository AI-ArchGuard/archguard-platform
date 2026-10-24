package io.github.aiarchguard.platform.repository.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.InvalidRepositoryException;
import io.github.aiarchguard.platform.repository.RepositoryConflictException;
import io.github.aiarchguard.platform.repository.RepositoryCatalog;
import io.github.aiarchguard.platform.repository.RepositoryNotFoundException;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.repository.RepositoryView;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Repository 应用服务：编排项目授权、输入校验、本地目录登记与成功审计。
 * 同时实现面向当前主体的 Operations 与可信内部查询用的 Catalog，两者的授权责任不同。
 * 本类没有整体事务声明，仓库写入与随后审计不能被理解为一个原子提交。
 */
@Service
final class RepositoryApplicationService implements RepositoryOperations, RepositoryCatalog {
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{2,62}");

    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final RepositoryStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;
    private final Path sourceRoot;

    /** 通过构造器取得跨模块公开接口；配置的根目录在这里规范化，存在性留到登记路径校验时检查。 */
    RepositoryApplicationService(ProjectAuthorization projects, CurrentActorProvider actors,
                                 RepositoryStore store, AuditRecorder audit, TraceIdProvider traceIds,
                                 Clock clock, @Value("${archguard.repositories.source-root:./sources}") String sourceRoot) {
        this.projects = projects;
        this.actors = actors;
        this.store = store;
        this.audit = audit;
        this.traceIds = traceIds;
        this.clock = clock;
        this.sourceRoot = Path.of(sourceRoot).toAbsolutePath().normalize();
    }

    /**
     * 先确认维护者权限，再校验业务字段与当前磁盘目录；这里登记现有目录，不下载或修改源码。
     * 校验通过后写入登记，并顺序记录成功审计；当前方法不补写校验失败或唯一键冲突的失败审计。
     */
    @Override
    public RepositoryView create(UUID projectId, String key, String name, String mountPath) {
        // 沿用 Project 的可见性隐藏规则：不存在或非成员为 404，可见的 Viewer 角色不足为 403。
        projects.requireMaintainer(projectId);
        validateKeyAndName(key, name);
        String normalizedPath = ControlledRepositoryPath.normalize(sourceRoot, mountPath);
        UUID actorId = actors.currentActor().id();
        UUID repositoryId = UUID.randomUUID();
        // Scanner identity 绑定项目与仓库 UUID，用于报告来源核对；记录版本从 0 开始，与 Git 提交无关。
        RepositoryView view = new RepositoryView(repositoryId, projectId, key, name.trim(), normalizedPath,
            "platform:" + projectId + ":" + repositoryId, actorId, Instant.now(clock), 0);
        try {
            store.insert(view);
        } catch (DuplicateKeyException exception) {
            // 数据库唯一约束处理并发重复登记；当前将所有唯一键冲突统一映射，未区分具体约束或重试。
            throw new RepositoryConflictException();
        }
        // 此调用发生在 insert 之后；本服务没有声明两者共同的事务，不能保证审计失败会撤销登记。
        audit.record(new AuditEvent(actorId, projectId, "repository.create", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("repositoryId", view.id(), "mountPath", normalizedPath)));
        return view;
    }

    /** 先以当前可信身份检查项目可读性，再返回该项目的全部登记，不重新访问源码目录。 */
    @Override
    public List<RepositoryView> list(UUID projectId) {
        projects.requireViewer(projectId);
        return store.list(projectId);
    }

    /** 对外读取先执行成员授权，再复用按项目和仓库双标识查询的 Catalog 逻辑。 */
    @Override
    public RepositoryView get(UUID projectId, UUID repositoryId) {
        projects.requireViewer(projectId);
        return requireRegistered(projectId, repositoryId);
    }

    /**
     * 内部查询只确认登记存在且属于给定项目，不获取当前主体或再次鉴权。
     * 后台协调器可按可信任务记录读取；面向用户的调用应走 get 或自行先完成授权。
     */
    @Override
    public RepositoryView requireRegistered(UUID projectId, UUID repositoryId) {
        return store.find(projectId, repositoryId).orElseThrow(RepositoryNotFoundException::new);
    }

    /** key 必须是 3–63 位的小写业务标识；名称按原始长度检查上限，通过后创建逻辑才执行 trim。 */
    private static void validateKeyAndName(String key, String name) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidRepositoryException("Repository key must match [a-z][a-z0-9-]{2,62}");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new InvalidRepositoryException("Repository name must contain between 1 and 120 characters");
        }
    }
}
