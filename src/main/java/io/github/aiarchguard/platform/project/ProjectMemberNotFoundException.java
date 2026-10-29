package io.github.aiarchguard.platform.project;

/**
 * 已通过项目管理授权后，要移除的成员关系不存在或删除未命中时的公开异常，Web 层映射为 404。
 * 它描述目标在该项目中的成员关系，不代表身份系统中的主体不存在。
 */
public final class ProjectMemberNotFoundException extends RuntimeException {
    public ProjectMemberNotFoundException() {
        super("Project member was not found");
    }
}
