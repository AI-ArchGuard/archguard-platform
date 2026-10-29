package io.github.aiarchguard.platform.governance;

import java.util.UUID;

/**
 * CI 报告验收的公开应用边界：验证报告/归属，复用扫描结果导入，再关联持久化门禁。
 * 验收、求值和完成关联分段持久化；中断后同键重放可补完 RECEIVED，不能把幂等理解为全链路恰好一次。
 */
public interface ReportSubmissionOperations {
    /**
     * 提交响应及是否复用原提交；HTTP 首次返回 202、重放返回 200，但这不证明使用了后台线程。
     * 当前实现会在 submit 调用中继续求值和完成关联，因此首次响应也可能已经是 COMPLETED。
     */
    record Acceptance(ReportSubmissionView submission, boolean replay) { }
    /**
     * 要求当前 Maintainer；报告须符合固定版本、字节摘要、UTF-8、Schema/引用闭合及已登记 GitHub 绑定。
     * Project 内同键异摘要冲突，同键同摘要或不同键同摘要复用原提交；不把报告排入本地 Runner。
     */
    Acceptance submit(UUID projectId, UUID repositoryId, String idempotencyKey,
                      ReportSubmissionMetadata metadata, byte[] report);
    /** Viewer 在明确项目/仓库范围读取提交状态，COMPLETED 只说明已关联门禁，不保证 PASS。 */
    ReportSubmissionView get(UUID projectId, UUID repositoryId, UUID submissionId);
    /** 读取完成提交所绑定的确切门禁；未完成时抛冲突，由 HTTP 轮询入口先处理为 202 状态响应。 */
    SubmissionGateView gate(UUID projectId, UUID repositoryId, UUID submissionId);
    /**
     * 为 Agent 摘要核验完成报告与已应用可信 PR head 修订的精确关联，先校验当前主体 Viewer 权限。
     * 这是历史修订与报告的匹配，不要求该修订此刻仍是 PR 最新 head，也不证明门禁通过。
     */
    boolean matchesCompletedPrRevision(UUID projectId, UUID scanJobId, String reportSha256,
                                       UUID prHeadRevisionId);
    /**
     * 同样的只读匹配，供内部显式主体调用链使用；实现先校验 actorId 的 Project Viewer 权限。
     * actorId 应来自可信调用上下文，这个接口不允许 HTTP 客户端以自报 UUID 取得身份。
     */
    boolean matchesCompletedPrRevisionForActor(UUID projectId, UUID actorId, UUID scanJobId,
                                               String reportSha256, UUID prHeadRevisionId);
}
