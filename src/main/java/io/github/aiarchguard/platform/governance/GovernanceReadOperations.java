package io.github.aiarchguard.platform.governance;

import java.util.UUID;

/**
 * 面向 Web 展示的治理只读应用边界，所有方法先验证当前主体的 Viewer 权限及 Repository 归属。
 * 返回持久化历史或可信修订的只读派生视图，不触发扫描、基线切换、门禁求值或外部状态发布。
 */
public interface GovernanceReadOperations {
    /**
     * 查询明确分支和 RuleSetVersion 下的门禁历史，可再按 PR 编号过滤，避免混排不同规则语义。
     * page 从 0 开始、size 为 1–50；结果按求值时间和 ID 降序，hasMore 由多取一条推导。
     */
    GovernancePage<GateEvaluationView> gates(UUID projectId, UUID repositoryId, String targetBranch,
            UUID ruleSetVersionId, String pullRequestId, int page, int size);
    /** 按项目/仓库范围读取封存的三类比较详情，不使用当前基线重算历史。 */
    ComparisonView comparison(UUID projectId, UUID repositoryId, UUID comparisonId);
    /** 分页读取已知 PR head 及其当前门禁指针；没有当前门禁不能解释为 PASS。 */
    GovernancePage<GithubPullRequestView> pullRequests(UUID projectId, UUID repositoryId, int page, int size);
    /**
     * 在一致读取快照中比较当前可信 head 与前一个不同可信 head 的完整候选 Finding 集。
     * 缺少可信前驱或可用报告时显式返回不可用；该结果只供解释 PR 变化，不参与基线门禁。
     */
    PrRevisionDeltaView prRevisionDelta(UUID projectId, UUID repositoryId, String externalId,
                                        UUID ruleSetVersionId);
}
