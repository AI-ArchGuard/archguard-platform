package io.github.aiarchguard.platform.finding.internal;

import java.util.List;
import java.util.UUID;

/**
 * 已通过报告校验的规范化数据写入端口，只归 Finding 模块所有；消费器不直接依赖 JDBC。
 * 此端口不保存原始报告，也不负责 ScanJob 的完成状态或用户授权。
 */
public interface FindingIngestionStore {
    /** 将本任务的 Evidence、Finding 及关联一起交给实现；重复写入策略由存储约束决定。 */
    void store(UUID jobId, List<NormalizedEvidence> evidences, List<NormalizedFinding> findings);
}
