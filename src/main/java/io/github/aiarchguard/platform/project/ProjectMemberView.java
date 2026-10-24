package io.github.aiarchguard.platform.project;

import java.time.Instant;
import java.util.UUID;

/**
 * 向模块调用方返回的不可变成员快照，仅包含主体 UUID、项目角色和本次成员关系的建立时间。
 * 更新已有成员的角色会保留 createdAt；此视图不包含身份目录中的用户资料。
 */
public record ProjectMemberView(UUID actorId, ProjectMemberRole role, Instant createdAt) {}
