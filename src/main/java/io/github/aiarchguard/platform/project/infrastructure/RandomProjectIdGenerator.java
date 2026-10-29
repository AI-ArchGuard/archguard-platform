package io.github.aiarchguard.platform.project.infrastructure;

import io.github.aiarchguard.platform.project.internal.application.ProjectIdGenerator;
import io.github.aiarchguard.platform.project.internal.domain.ProjectId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 项目标识端口的生产适配器：用随机 UUID 分配项目主键，再包装成领域值对象。
 * 随机生成被限制在基础设施层，使创建用例可替换该来源，领域模型无需调用全局随机数。
 */
@Component
final class RandomProjectIdGenerator implements ProjectIdGenerator {
    /** 只分配标识，不写入数据库；真正的项目创建由应用事务完成。 */
    @Override
    public ProjectId nextId() {
        return new ProjectId(UUID.randomUUID());
    }
}
