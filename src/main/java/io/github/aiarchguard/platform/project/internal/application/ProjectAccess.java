package io.github.aiarchguard.platform.project.internal.application;

import io.github.aiarchguard.platform.project.internal.domain.Project;
import io.github.aiarchguard.platform.project.internal.domain.ProjectRole;

/**
 * 应用层内部的一次成员授权查询结果：把项目事实与被查询操作者在该项目中的角色放在一起。
 * 角色属于成员关系而非项目本身，写用例据此区分可读成员与可管理项目的维护者。
 */
public record ProjectAccess(Project project, ProjectRole role) {}
