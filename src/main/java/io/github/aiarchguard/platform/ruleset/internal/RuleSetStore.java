package io.github.aiarchguard.platform.ruleset.internal;

import io.github.aiarchguard.platform.ruleset.RuleSetVersionView;
import io.github.aiarchguard.platform.ruleset.RuleSetView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 规则集模块自己的存储端口，区分元数据与追加的 YAML 版本，使应用流程不依赖 JDBC。
 * 查询中的项目、仓库条件限定数据归属，不承担用户授权；各方法也不声明组合事务或并发锁定。
 */
public interface RuleSetStore {
    /** 保存规则集元数据；仓库内 key 的唯一性由持久化约束兜底。 */
    void insert(RuleSetView ruleSet);

    /** 列出指定项目、仓库下的元数据，当前 JDBC 实现按创建时间及 ID 排序。 */
    List<RuleSetView> list(UUID projectId, UUID repositoryId);

    /** 在完整归属范围内查询规则集，避免仅凭全局 ID 取回其他仓库的数据。 */
    Optional<RuleSetView> find(UUID projectId, UUID repositoryId, UUID ruleSetId);

    /** 读取当前最大版本号加一作为候选值；此读取本身不预留编号，也不串行化并发提交。 */
    int nextVersion(UUID ruleSetId);

    /** 追加原文和追溯信息；同一规则集的版本号唯一约束拒绝重复编号，不提供自动重试。 */
    void insertVersion(RuleSetVersionView version);

    /** 按版本号倒序返回规则集的历史版本，不执行项目成员鉴权。 */
    List<RuleSetVersionView> listVersions(UUID ruleSetId);

    /** 通过元数据归属核对版本是否属于指定项目和仓库，供 Catalog 查询使用。 */
    Optional<RuleSetVersionView> findVersion(UUID projectId, UUID repositoryId, UUID versionId);

    /** 查询项目是否仍有规则集，供本模块的 Project 删除保护器使用。 */
    boolean existsForProject(UUID projectId);
}
