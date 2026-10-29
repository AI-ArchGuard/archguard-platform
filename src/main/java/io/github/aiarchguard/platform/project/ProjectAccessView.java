package io.github.aiarchguard.platform.project;

import java.util.UUID;

/**
 * 跨模块授权检查返回的最小项目快照，包含项目标识、展示信息和被检查主体的成员角色。
 * role 表示本次查询时的授权事实；后续操作仍应在自己的执行边界复核所需权限。
 */
public record ProjectAccessView(UUID projectId, String key, String name, ProjectMemberRole role) {}
