package io.github.aiarchguard.platform.ruleset.internal;

import io.github.aiarchguard.platform.project.ProjectDeletionGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 将规则集的存在性检查接入 Project 删除保护 SPI，避免 Project 直接查询 ruleset 表。
 * 只要项目下还有规则集元数据就阻止删除，无需逐个读取其 YAML 版本。
 */
@Component
final class RuleSetProjectDeletionGuard implements ProjectDeletionGuard {
    private final RuleSetStore store;
    RuleSetProjectDeletionGuard(RuleSetStore store){this.store=store;}
    /** 返回查询时的存在性结果；此检查本身不锁定后续规则集创建与项目删除。 */
    @Override public boolean hasContent(UUID projectId){return store.existsForProject(projectId);}
}
