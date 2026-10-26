package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.ReportSubmissionView;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 报告提交生命周期及其导入任务关联的内部存储端口，供提交编排和 GitHub 当前门禁关联使用。
 * 授权、报告验证与事务由应用服务负责；存储查询按明确 Project/Repository 范围限制资源。
 */
public interface ReportSubmissionStore {
    /** 对外提交视图加内部导入任务、原键和版本字段；恢复继续使用这一份任务。 */
    record Stored(ReportSubmissionView view, String idempotencyKey, UUID scanJobId,
                  String scannerVersion, String schemaVersion) { }
    /** 幂等键只在 Project 内唯一，不能用于跨项目命中或证明调用者权限。 */
    Optional<Stored> byKey(UUID projectId, String key);
    Optional<Stored> byDigest(UUID projectId, String digest);
    Optional<Stored> find(UUID projectId, UUID repositoryId, UUID id);
    /** Webhook 晚于报告时，为给定 head 查找最新完成门禁，再由 head 条件更新决定能否挂接。 */
    Optional<UUID> latestCompletedGateForPullRequest(UUID projectId, UUID repositoryId,
                                                     String externalId, String headSha);
    /** 校验任务、摘要和已保存签名修订的完成关联，不要求该修订仍然是当前 head。 */
    boolean matchesCompletedPrRevision(UUID projectId, UUID scanJobId, String reportSha256,
                                       UUID prHeadRevisionId);
    /** 首次插入 RECEIVED；Project 内键或摘要冲突时返回 false，调用方读取并核对胜者。 */
    boolean insert(ReportSubmissionView value, String key, UUID scanJobId,
                   String scannerVersion, String schemaVersion, UUID actorId);
    /** 仅把尚无门禁的 RECEIVED 条件更新为 COMPLETED；返回是否由本次调用完成。 */
    boolean complete(UUID projectId, UUID repositoryId, UUID id, UUID gateId, Instant at);
}
