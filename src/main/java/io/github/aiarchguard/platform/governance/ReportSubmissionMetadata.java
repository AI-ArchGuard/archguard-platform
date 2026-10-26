package io.github.aiarchguard.platform.governance;

import java.util.UUID;

/**
 * multipart 提交中的 metadata 部分，与原始报告字节一起构成版本化请求摘要输入。
 * Project/Repository 由 URL 确定；此记录只是认证提交者的元数据断言，不验签或推进 PR 当前 head。
 *
 * @param ruleSetVersionId 必须属于 URL 中仓库的不可变规则版本
 * @param revision 报告对应的提供方仓库、commit 与目标分支
 * @param pullRequest 可选 PR 引用；存在时 head 必须与 revision.commitSha 一致
 * @param scannerVersion 当前提交契约固定接受 0.2.1
 * @param schemaVersion 当前提交契约固定接受 Scanner Result 0.1.0
 * @param reportSha256 原始 UTF-8 报告字节的 SHA-256，不对重新序列化 JSON 求摘要
 */
public record ReportSubmissionMetadata(UUID ruleSetVersionId, GitRevision revision,
                                       PullRequestRef pullRequest, String scannerVersion,
                                       String schemaVersion, String reportSha256) { }
