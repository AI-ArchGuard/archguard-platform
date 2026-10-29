package io.github.aiarchguard.platform.project.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 单个项目成员的不可变领域快照，要求主体、角色和加入时间完整。
 * 记录不包含 projectId，项目归属由应用及仓储调用的上下文确定。
 * 最后一名维护者保护需要检查整组成员，不能由单个成员对象独自完成。
 */
public record ProjectMember(UUID actorId, ProjectRole role, Instant createdAt) {
    public ProjectMember {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
