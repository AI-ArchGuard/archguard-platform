package io.github.aiarchguard.platform.governance.internal;

import java.util.Optional;
import java.util.UUID;

/**
 * PR 修订差异的内部只读查询端口，为指定完整范围找到已完成报告关联的门禁及比较。
 * 授权、可信前驱和一致快照由 GovernanceReadService 组织；此查询不参与基线门禁求值。
 */
public interface PrRevisionDeltaStore {
    /** 关联到不可变比较的最小候选引用，算法版本用于拒绝不兼容集合运算。 */
    record Candidate(UUID gateId, UUID comparisonId, String fingerprintVersion) { }
    /** 同 Project/Repository/PR/head/分支/规则版本内取最新完成且有比较的门禁，缺失不猜测为空结果。 */
    Optional<Candidate> candidate(UUID projectId, UUID repositoryId, String externalId,
                                  String headSha, String targetBranch, UUID ruleSetVersionId);
}
