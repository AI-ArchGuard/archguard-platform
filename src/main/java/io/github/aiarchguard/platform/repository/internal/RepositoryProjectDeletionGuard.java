package io.github.aiarchguard.platform.repository.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 向 Project 的删除保护扩展点报告本模块是否仍有仓库登记，使 Project 无需访问 Repository 的表。
 * 此查询仅是删除前置检查，不锁定并发登记，也不提供跨模块检查与删除的全局原子性。
 */
@Component
final class RepositoryProjectDeletionGuard implements ProjectDeletionGuard {
    private final RepositoryStore store;
    RepositoryProjectDeletionGuard(RepositoryStore store) { this.store=store; }
    /** 只要存在一条仓库登记就阻止项目删除，不读取磁盘或检查目录是否仍存在。 */
    @Override public boolean hasContent(UUID projectId) { return store.existsForProject(projectId); }
}
