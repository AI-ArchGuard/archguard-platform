package io.github.aiarchguard.platform.ruleset;

import java.util.List;
import java.util.UUID;

/**
 * 面向规则集管理入口的应用用例：先建立仓库下的规则集元数据，再追加不可变的 YAML 版本。
 * 实现负责通过 Project 公开接口检查成员角色，Web 层只做请求转换。
 */
public interface RuleSetOperations {
    /** 要求项目维护者，核对仓库归属后创建 key 和名称等元数据；此时尚无规则 YAML。 */
    RuleSetView create(UUID projectId, UUID repositoryId, String key, String name);

    /** 要求项目查看者，列出指定仓库下的规则集元数据。 */
    List<RuleSetView> list(UUID projectId, UUID repositoryId);

    /** 要求项目维护者，校验 YAML 与仓库身份后追加版本，并保存原文、摘要及契约版本。 */
    RuleSetVersionView createVersion(UUID projectId, UUID repositoryId, UUID ruleSetId, String yaml);

    /** 要求项目查看者，确认规则集归属后按版本号倒序返回其版本。 */
    List<RuleSetVersionView> listVersions(UUID projectId, UUID repositoryId, UUID ruleSetId);
}
