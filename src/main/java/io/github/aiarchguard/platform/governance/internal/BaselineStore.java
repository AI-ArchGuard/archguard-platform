package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.BaselineVersionView;
import io.github.aiarchguard.platform.governance.ClassifiedFinding;
import io.github.aiarchguard.platform.governance.ComparisonView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 基线与比较的内部持久化端口，由应用服务负责授权、归属校验和事务。
 * JDBC 适配器负责空间锁、写入后封存与历史读取；传入资源 ID 本身不代表访问许可。
 */
public interface BaselineStore {
    /** 调用方须在事务内使用；创建或锁定同一比较空间，以分配版本和更新活动选择。 */
    BaselineScope lockOrCreate(UUID projectId, UUID repositoryId, String branch, UUID ruleSetVersionId);
    Optional<BaselineScope> scope(UUID projectId, UUID repositoryId, String branch, UUID ruleSetVersionId);
    Optional<BaselineVersionView> byJob(UUID scopeId, UUID jobId);
    Optional<BaselineVersionView> version(UUID scopeId, UUID versionId);
    List<BaselineVersionView> versions(UUID scopeId);
    /** 在已锁定空间中写入版本与 Finding 快照并封存，推进内容版本号；不自动选择为活动版本。 */
    BaselineVersionView insertVersion(BaselineScope scope, UUID jobId, String commitSha, String reportSha,
                                      String algorithm, UUID actorId, Instant now, List<FindingSnapshot> findings);
    /** 更新活动指针并追加选择史；即使再次选择同一版本也增加 selectionVersion。 */
    BaselineVersionView select(BaselineScope scope, UUID versionId, UUID actorId, Instant now);
    List<FindingSnapshot> findings(UUID baselineVersionId);
    Optional<ComparisonView> comparison(UUID baselineVersionId, UUID candidateJobId);
    Optional<ComparisonView> findComparison(UUID projectId, UUID repositoryId, UUID comparisonId);
    /** 保存并封存确切基线版本与候选任务的分类结果；上层持有空间锁并负责重复请求检查。 */
    ComparisonView insertComparison(UUID baselineVersionId, UUID candidateJobId, String reportSha,
                                    String algorithm, Instant now, List<ClassifiedFinding> findings);
}
