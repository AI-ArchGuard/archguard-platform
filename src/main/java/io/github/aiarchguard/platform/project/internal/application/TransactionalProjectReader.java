package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.Project;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目的只读事务入口，由外层应用服务通过 Spring 代理调用。
 * 查询使用成员关系限制可见范围，返回领域数据和 Optional，由外层决定公开视图及异常语义。
 * readOnly 声明读取意图，并不自动提供跨多条 SQL 的固定快照或排他锁。
 */
@Service
public class TransactionalProjectReader {
    private final ProjectRepository repository;

    public TransactionalProjectReader(ProjectRepository repository) {
        this.repository = repository;
    }

    /** 将项目不存在和操作者不是成员都表示为空，避免向非成员泄露项目是否存在。 */
    @Transactional(readOnly = true)
    public Optional<Project> findForActor(UUID projectId, UUID actorId) {
        return repository.findForActor(projectId, actorId);
    }

    /** 在同一次查询中带回操作者的成员角色，供 Viewer/Maintainer 授权入口使用。 */
    @Transactional(readOnly = true)
    public Optional<ProjectAccess> findAccessForActor(UUID projectId, UUID actorId) {
        return repository.findAccessForActor(projectId, actorId);
    }

    /**
     * 把零基页码换成记录偏移，再分别读取可见项目与总数。
     * 先转 long 再相乘，避免 int 乘法溢出；两次查询不承诺在并发写入下拥有同一快照。
     */
    @Transactional(readOnly = true)
    public ProjectPageData listForActor(UUID actorId, int page, int size) {
        return new ProjectPageData(
            repository.findPageForActor(actorId, size, (long) page * size),
            repository.countForActor(actorId));
    }

    /**
     * 先确认操作者可见该项目，再读取该项目的全部角色成员；Viewer 也可以查看成员名单。
     * 仓储的成员分页方法不含操作者条件，因此不能跳过这里的可见性检查。
     */
    @Transactional(readOnly = true)
    public Optional<ProjectMemberPageData> listMembersForActor(UUID projectId, UUID actorId, int page, int size) {
        if (repository.findForActor(projectId, actorId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProjectMemberPageData(
            repository.findMemberPage(projectId, size, (long) page * size),
            repository.countMembers(projectId)));
    }
}
