package io.github.aiarchguard.platform.project;

/**
 * 公开应用边界使用的项目成员角色，与内部领域枚举分别建模以保持模块封装。
 * 项目角色控制成员的读写能力；创建项目所需的全局 project:create 权限由身份权限另行检查。
 */
public enum ProjectMemberRole {
    /** 可读取项目，并执行项目修改、删除和成员管理。 */
    MAINTAINER,
    /** 可读取项目和成员信息，不能执行需要维护者权限的操作。 */
    VIEWER
}
