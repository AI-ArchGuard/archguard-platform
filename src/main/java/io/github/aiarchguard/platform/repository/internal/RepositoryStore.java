package io.github.aiarchguard.platform.repository.internal;

import io.github.aiarchguard.platform.repository.RepositoryView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository 模块内部的持久化端口，由 JDBC 适配器实现；其他业务模块应使用公开用例或 Catalog。
 * 此端口处理登记事实，不执行成员授权、路径检查或审计，也不声明跨调用事务。
 */
public interface RepositoryStore {
    /** 插入已由应用层组装的登记；唯一键冲突交由应用层映射为业务异常。 */
    void insert(RepositoryView repository);

    /** 返回指定项目的全部登记，排序由当前存储实现确定。 */
    List<RepositoryView> list(UUID projectId);

    /** 同时限定项目与仓库，避免只凭仓库 UUID 读取其他项目的资源；未匹配时返回空值。 */
    Optional<RepositoryView> find(UUID projectId, UUID repositoryId);

    /** 为项目删除保护查询是否仍有登记；该读操作本身不锁住后续仓库创建。 */
    boolean existsForProject(UUID projectId);
}
