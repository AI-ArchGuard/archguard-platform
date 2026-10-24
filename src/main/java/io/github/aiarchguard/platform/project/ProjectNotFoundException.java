package io.github.aiarchguard.platform.project;

/**
 * 项目不存在，或被检查主体没有该项目成员关系时统一使用的公开异常，Web 层映射为 404。
 * 两种情况共享结果，避免非成员通过错误差异枚举其他项目是否存在。
 */
public final class ProjectNotFoundException extends RuntimeException {
    public ProjectNotFoundException() {
        super("Project was not found");
    }
}
