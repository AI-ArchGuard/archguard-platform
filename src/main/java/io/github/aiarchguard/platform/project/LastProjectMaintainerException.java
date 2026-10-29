package io.github.aiarchguard.platform.project;

/**
 * 降级或移除成员会使项目失去最后一名维护者时的业务冲突，Web 层映射为 409。
 * 该约束由掌握整个项目成员集合的应用服务检查，避免项目失去后续管理入口。
 */
public final class LastProjectMaintainerException extends RuntimeException {
    public LastProjectMaintainerException() {
        super("A project must retain at least one maintainer");
    }
}
