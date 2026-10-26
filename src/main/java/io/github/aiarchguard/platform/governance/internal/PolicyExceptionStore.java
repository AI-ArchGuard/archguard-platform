package io.github.aiarchguard.platform.governance.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 例外创建事实及独立撤销事实的内部存储端口，供授权后的应用用例与门禁策略读取。
 * 端口不判定当前主体权限，也不按时间修改过期状态；事务和审计由应用层组织。
 */
public interface PolicyExceptionStore {
    void insert(PolicyExceptionRecord value);
    Optional<PolicyExceptionRecord> find(UUID projectId, UUID repositoryId, UUID exceptionId);
    /** 读取完整比较空间的全部记录；是否有效由求值时间决定，不在查询时提前丢弃历史。 */
    List<PolicyExceptionRecord> list(UUID projectId, UUID repositoryId, String branch, UUID ruleSetVersionId);
    /** 只允许首次追加撤销；已有撤销时返回 false，调用方须核对原原因。 */
    boolean revoke(UUID exceptionId, UUID versionId, String reason, UUID actorId, Instant at);
}
