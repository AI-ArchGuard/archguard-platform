package io.github.aiarchguard.platform.repository;

import java.util.List;
import java.util.UUID;

/**
 * 面向当前可信认证主体的仓库用例边界，供 HTTP 入口和其他业务模块调用。
 * 实现通过 Project 的公开授权接口检查角色，避免调用方读取项目成员表或仓库内部存储。
 */
public interface RepositoryOperations {
    /**
     * 要求项目维护者，登记受控源码根下已存在的本地目录，并生成仓库标识和创建审计。
     * mountPath 是本地相对路径，不是 Git URL；此用例不克隆代码或创建目录。
     */
    RepositoryView create(UUID projectId, String key, String name, String mountPath);

    /** 要求项目成员，返回该项目全部仓库登记；当前接口没有分页参数。 */
    List<RepositoryView> list(UUID projectId);

    /** 要求项目成员，再按项目与仓库标识查询；与不检查当前主体的 Catalog 入口区分。 */
    RepositoryView get(UUID projectId, UUID repositoryId);
}
