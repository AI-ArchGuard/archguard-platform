package io.github.aiarchguard.platform.finding.internal;

import io.github.aiarchguard.platform.finding.DispositionView;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingDisposition;
import io.github.aiarchguard.platform.finding.FindingView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 应用服务使用的 Finding 查询与人工处置存储端口，返回公开视图而不暴露数据库对象。
 * 项目权限由调用方先检查；资源查询仍同时限定项目和任务，历史查询则依赖应用层先核实归属。
 */
public interface FindingStore {
    /** 按页读取指定项目和任务的 Finding，排序与位置换算由适配器完成。 */
    List<FindingView> list(UUID projectId, UUID jobId, int page, int size);
    /** 精确匹配资源归属，未匹配时返回空值而不泄露其他项目的数据。 */
    Optional<FindingView> find(UUID projectId, UUID jobId, UUID findingId);
    Optional<EvidenceView> findEvidence(UUID projectId, UUID jobId, UUID evidenceId);
    /**
     * 以 expectedVersion 做条件更新；匹配才递增版本并追加历史，否则返回 false。
     * previous 由调用方提供给历史记录；外部审计不属于此端口的事务。
     */
    boolean updateDisposition(UUID findingId, FindingDisposition previous, FindingDisposition next,
                              long expectedVersion, String reason, UUID actorId, Instant now, UUID historyId);
    /** 读取已由调用方核实归属的 Finding 历史。 */
    List<DispositionView> history(UUID findingId);
}
