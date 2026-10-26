package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

/**
 * Platform 已知的 GitHub PR 当前状态，供 CI/Web 识别当前 head，来源是验签并实际应用的事件。
 * head 按签名正文中的事件时间推进，不代表 Platform 查询过 GitHub 实时状态或证明过 Git 祖先关系。
 *
 * @param projectId 已绑定的项目
 * @param repositoryId 已绑定的仓库
 * @param externalId GitHub PR 数字编号
 * @param headSha 当前已应用事件的 head SHA
 * @param baseSha 同一事件声明的 base SHA
 * @param targetBranch PR 的目标分支
 * @param eventAt 签名正文 pull_request.updated_at，作为时间窗口和顺序依据
 * @param currentGateEvaluationId 绑定当前 head 的完成门禁；为空时应显示待评估，不能借用旧门禁
 * @param currentHeadRevisionId 已持久化的可信 head 修订 ID，源自 delivery ID；旧历史可能为空
 */
public record GithubPullRequestView(UUID projectId, UUID repositoryId, String externalId,
                                    String headSha, String baseSha, String targetBranch,
                                    Instant eventAt, UUID currentGateEvaluationId,
                                    UUID currentHeadRevisionId) { }
