package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 有期限治理例外的公开应用边界，例外独立于 Finding 人工处置、原始报告、基线和历史门禁。
 * 当前主体的 Maintainer 权限用于创建/撤销，Viewer 权限用于读取；所有范围均限定到项目和仓库。
 */
public interface PolicyExceptionOperations {
    /**
     * 在明确分支/规则版本内创建单指纹或单规则例外，原因须有效，生效到到期不超过 30 天且到期在未来。
     * 求值有效区间为 [effectiveAt, expiresAt)，还要求例外已创建且在该时刻尚未撤销。
     */
    PolicyExceptionView create(UUID projectId, UUID repositoryId, String targetBranch,
                               UUID ruleSetVersionId, PolicyExceptionScopeType scopeType, String scopeValue,
                               String reason, Instant effectiveAt, Instant expiresAt);
    /** 追加不可变撤销事实；同原因重复撤销返回原事实，改用另一原因则冲突，不重写已有门禁。 */
    PolicyExceptionView revoke(UUID projectId, UUID repositoryId, UUID exceptionId, String reason);
    /** 列出同一比较空间的例外，状态按本次服务器时间派生，到期无需更新历史行。 */
    List<PolicyExceptionView> list(UUID projectId, UUID repositoryId, String targetBranch, UUID ruleSetVersionId);
    /** 读取指定例外及其创建/撤销信息，返回当前时间下的状态。 */
    PolicyExceptionView get(UUID projectId, UUID repositoryId, UUID exceptionId);
}
