package io.github.aiarchguard.platform.project;

import java.time.Instant;
import java.util.UUID;

/**
 * 项目公开应用边界的不可变状态快照，供跨模块使用及 Web 响应映射。
 * createdBy 记录创建事实，当前权限取决于成员关系；version 用于名称修改和删除的并发校验，
 * 成员角色变化不会递增该版本，因此不能用它判断成员授权是否仍然有效。
 */
public record ProjectView(UUID id, String key, String name, UUID createdBy, Instant createdAt, long version) {}
