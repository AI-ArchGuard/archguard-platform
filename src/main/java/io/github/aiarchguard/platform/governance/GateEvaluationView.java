package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 已封存门禁的公开事实，供直接门禁 API、历史列表和报告关联使用；读取时不重新执行策略。
 * 默认策略仅阻断未被有效例外覆盖的 NEW high/critical；EXISTING 与 RESOLVED 不阻断。
 *
 * @param id 不可变门禁 ID
 * @param projectId 求值所属项目
 * @param repositoryId 求值所属仓库
 * @param targetBranch 比较空间中的目标分支
 * @param ruleSetVersionId 隔离本次语义的规则版本
 * @param candidateJobId 候选扫描任务
 * @param comparisonId 使用的比较事实，错误结果可能为空
 * @param baselineVersionId 使用的确切基线版本，错误结果可能为空
 * @param outcome PASS、FAIL 或 ERROR，不包含提交处理生命周期
 * @param ciExitCode PASS 为 0，FAIL 为 2，配置错误为 64，执行或契约错误为 70
 * @param errorKind ERROR 的类别，正常 PASS/FAIL 为空
 * @param errorCode 可供调用者判断的稳定错误代码，正常结果为空
 * @param policyVersion 求值时采用的门禁策略版本
 * @param fingerprintVersion 比较采用的逻辑指纹算法版本
 * @param newCount 基线比较中的新增数量
 * @param existingCount 基线比较中的存量数量
 * @param resolvedCount 基线比较中已解决数量，不是 PR 前后修订的修复数量
 * @param blockedCount 新增高风险问题中未被有效例外覆盖的数量
 * @param matchedExceptionVersionIds 求值时命中的确切例外版本，后续撤销不改写该列表
 * @param evaluatedAt 用于判断例外有效性的求值时间
 */
public record GateEvaluationView(UUID id, UUID projectId, UUID repositoryId, String targetBranch,
                                 UUID ruleSetVersionId, UUID candidateJobId, UUID comparisonId,
                                 UUID baselineVersionId, GateOutcome outcome, int ciExitCode,
                                 GateErrorKind errorKind, String errorCode, String policyVersion,
                                 String fingerprintVersion, long newCount, long existingCount,
                                 long resolvedCount, long blockedCount,
                                 List<UUID> matchedExceptionVersionIds, Instant evaluatedAt) {
    /** 复制命中列表，使历史视图不受调用方后续修改影响。 */
    public GateEvaluationView { matchedExceptionVersionIds = List.copyOf(matchedExceptionVersionIds); }
}
