package io.github.aiarchguard.platform.finding;

import java.util.List;
import java.util.UUID;

/**
 * Finding 模块对 HTTP 等调用方公开的查询与人工处置用例，隐藏内部 JDBC 实现。
 * 实现先通过 Project 校验当前操作者：查询要求 Viewer，修改处置要求 Maintainer。
 */
public interface FindingOperations {
    /** 分页读取指定任务的 Finding；页码从零开始，每页 1–100 条，仅返回列表而非总数。 */
    List<FindingView> list(UUID projectId, UUID jobId, int page, int size);
    /** 在项目和任务范围内读取单条 Finding；不存在时抛出 FindingNotFoundException。 */
    FindingView get(UUID projectId, UUID jobId, UUID findingId);
    /** 在同一项目和任务范围内读取证据，避免仅凭全局 UUID 越过资源归属。 */
    EvidenceView evidence(UUID projectId, UUID jobId, UUID evidenceId);
    /**
     * 以非空原因和预期版本修改本次扫描的人工状态，写处置历史并调用审计。
     * 版本不匹配时拒绝覆盖；不修改 Scanner 原始事实或其他扫描的处置。
     */
    FindingView disposition(UUID projectId, UUID jobId, UUID findingId, FindingDisposition disposition,
                            String reason, long expectedVersion);
    /** 确认 Finding 属于所给项目和任务后，读取其追加处置历史。 */
    List<DispositionView> dispositionHistory(UUID projectId, UUID jobId, UUID findingId);
}
