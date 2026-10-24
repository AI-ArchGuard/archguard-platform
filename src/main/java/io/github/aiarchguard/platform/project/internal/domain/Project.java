package io.github.aiarchguard.platform.project.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 领域层的不可变项目状态：值对象约束标识、key 和名称，创建者、时间及版本记录项目事实。
 * ID 和时间由应用层传入，对象自身不读取随机数或系统时钟，便于测试和持久化重建。
 * 成员权限与至少一名维护者的约束需要项目成员集合，由应用服务另行协调。
 */
public record Project(ProjectId id, ProjectKey key, ProjectName name, UUID createdBy, Instant createdAt,
                      long version) {
    /** 新建与从存储重建共用完整性检查，避免领域状态携带缺失字段或无效版本。 */
    public Project {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        // 初始版本为 0，名称更新后递增；负版本无法表达有效的项目状态。
        if (version < 0) {
            throw new IllegalArgumentException("Project version must not be negative");
        }
    }
}
