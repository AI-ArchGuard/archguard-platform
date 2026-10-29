package io.github.aiarchguard.platform.project.internal.domain;

/**
 * 领域内部的项目成员角色，与公开边界的 ProjectMemberRole 保持各自类型。
 * 应用服务根据角色执行权限检查，领域枚举不依赖 Web、安全框架或数据库实现。
 */
public enum ProjectRole {
    /** 具备项目读取和管理能力，也是项目必须至少保留一名的角色。 */
    MAINTAINER,
    /** 具备项目读取能力，不能执行维护者命令。 */
    VIEWER
}
