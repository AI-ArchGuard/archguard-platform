package io.github.aiarchguard.platform.project;

/**
 * 缺少 project:create 全局权限，或已可见项目的成员角色不足时的公开异常，Web 层映射为 403。
 * 非成员的项目访问通过未找到语义隐藏存在性，不使用此异常暴露该项目。
 */
public final class ProjectPermissionDeniedException extends RuntimeException {
    public ProjectPermissionDeniedException() {
        super("Project operation is not permitted");
    }
}
