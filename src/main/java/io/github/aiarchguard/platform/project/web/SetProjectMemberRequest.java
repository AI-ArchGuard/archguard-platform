package io.github.aiarchguard.platform.project.web;

import io.github.aiarchguard.platform.project.ProjectMemberRole;
import jakarta.validation.constraints.NotNull;

/** 成员角色设置输入：目标成员 ID 来自路径，role 只表示该项目内的维护者或查看者角色。 */
record SetProjectMemberRequest(@NotNull ProjectMemberRole role) {}
