package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

/**
 * CI 报告提交的公开元数据与进度，不携带原始报告或 Git 凭据；查询和重放都返回同一提交身份。
 *
 * @param id 稳定提交 ID
 * @param projectId 幂等键与请求摘要的隔离项目
 * @param repositoryId URL 指定并已核验归属的仓库
 * @param ruleSetVersionId 报告绑定的不可变规则版本
 * @param revision 经过格式/绑定校验的提交者 Git 断言，不等于验签 Webhook head
 * @param pullRequest 可选的提交者 PR 断言
 * @param reportSha256 原始报告字节摘要
 * @param requestDigest 按版本化固定字段顺序计算的整个提交摘要，不只是 reportSha256
 * @param status 接收/完成生命周期，不能据此判断门禁是否通过
 * @param gateEvaluationId 完成后关联的确切门禁 ID，RECEIVED 时为空
 * @param createdAt 提交首次持久化时间，重放不重新创建提交
 */
public record ReportSubmissionView(UUID id, UUID projectId, UUID repositoryId, UUID ruleSetVersionId,
                                   GitRevision revision, PullRequestRef pullRequest, String reportSha256,
                                   String requestDigest, ReportSubmissionStatus status,
                                   UUID gateEvaluationId, Instant createdAt) { }
