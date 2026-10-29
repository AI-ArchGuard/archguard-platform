package io.github.aiarchguard.platform.repository;

import java.util.UUID;

/**
 * 供可信内部流程查询已登记仓库的公开边界，例如后台扫描协调器取得路径和 Scanner identity。
 * 此接口只确认项目与仓库的归属记录，不读取当前操作者或执行成员授权；调用方负责可信调用上下文。
 */
public interface RepositoryCatalog {
    /**
     * 按项目、仓库两个 UUID 精确查询，找不到匹配登记时抛出 {@link RepositoryNotFoundException}。
     * UUID 只是资源标识，不证明访问权限；本查询也不重新检查磁盘目录是否仍然有效。
     */
    RepositoryView requireRegistered(UUID projectId, UUID repositoryId);
}
