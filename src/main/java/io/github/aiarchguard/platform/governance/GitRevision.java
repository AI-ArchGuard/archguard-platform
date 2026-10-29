package io.github.aiarchguard.platform.governance;

/**
 * 报告提交所绑定的 Git 修订元数据，属于公开输入 DTO；语义校验由提交流程执行，record 本身不验签。
 * 这些值是认证提交者的断言，不能自行更新 PR 当前 head 或证明 Git 祖先关系。
 *
 * @param provider Git 提供方，当前提交契约只接受 github
 * @param providerRepositoryId 已登记绑定中的稳定数字仓库 ID
 * @param commitSha 本报告扫描的提交，要求 40 或 64 位小写十六进制
 * @param targetBranch 本次治理比较的目标分支，由应用层校验并规范化为 NFC
 */
public record GitRevision(String provider, String providerRepositoryId, String commitSha,
                          String targetBranch) { }
