package io.github.aiarchguard.platform.ruleset;

import java.util.UUID;

/**
 * 提供给扫描和治理等模块的规则版本查询边界，调用方无需访问规则集表或内部存储。
 * 此目录只核对版本所属的项目与仓库，不执行当前主体的项目成员鉴权。
 */
public interface RuleSetCatalog {
    /**
     * 返回指定项目、仓库下的确切版本；不存在或归属不匹配时报告未找到。
     * 调用方须自行完成相应用户或后台流程的授权，传入 UUID 本身不是身份证明。
     */
    RuleSetVersionView requireVersion(UUID projectId, UUID repositoryId, UUID versionId);
}
