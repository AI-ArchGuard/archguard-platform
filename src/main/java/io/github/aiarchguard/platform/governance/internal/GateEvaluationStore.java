package io.github.aiarchguard.platform.governance.internal;

import io.github.aiarchguard.platform.governance.GateEvaluationView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 门禁历史的内部存储端口，供求值写服务与只读应用服务使用；不自行验证调用者权限。
 * 数据库适配器按 Project 内幂等键去重并封存结果，应用层负责事务、摘要冲突及归属检查。
 */
public interface GateEvaluationStore {
    /** 伴随历史结果返回原请求摘要，用于重放时核对输入。 */
    record Stored(GateEvaluationView view, String requestSha256) { }
    Optional<Stored> byKey(UUID projectId, String key);
    Optional<GateEvaluationView> find(UUID projectId, UUID repositoryId, UUID id);
    /** 在确切分支/规则版本内按求值时间及 ID 倒序取历史，可按 PR 进一步过滤。 */
    List<GateEvaluationView> list(UUID projectId, UUID repositoryId, String branch,
            UUID ruleSetVersionId, String pullRequestId, long offset, int limit);
    /** 写入门禁和命中版本；同 Project 的键已存在时返回 false，由调用方核对摘要。 */
    boolean insert(GateEvaluationView value, String key, String requestSha256, UUID actorId);
    /** 完成组装后封存，防止继续追加例外命中；须与 insert 处于同一写事务。 */
    void seal(UUID id);
}
