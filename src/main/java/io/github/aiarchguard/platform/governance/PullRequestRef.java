package io.github.aiarchguard.platform.governance;

/**
 * 报告元数据中可选的 PR 断言，由提交流程校验；这个 DTO 不等于 Webhook 建立的可信当前 PR 状态。
 *
 * @param externalId GitHub PR 的数字编号
 * @param headSha 此报告的 PR head，必须等于同一元数据中的 revision.commitSha
 * @param baseSha 提交者声明的 PR base，格式合法也不证明它是 head 的 Git 祖先
 */
public record PullRequestRef(String externalId, String headSha, String baseSha) { }
