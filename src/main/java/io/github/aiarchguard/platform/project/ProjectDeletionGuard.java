package io.github.aiarchguard.platform.project;

import java.util.UUID;

/**
 * 由拥有项目子资源的模块实现的删除前置检查扩展点，使 Project 无需访问其他模块的表。
 * Project 应用服务在确认维护者权限后查询已装配的保护器，任一返回 true 即阻止删除。
 */
public interface ProjectDeletionGuard {
    /**
     * 返回本模块是否有阻止该项目删除的内容或状态；无法安全确认时，实现也可以保守阻止。
     * 当前调用发生在项目删除事务之前，此查询本身不提供跨模块并发锁定或检查与删除的原子性。
     */
    boolean hasContent(UUID projectId);
}
