package io.github.aiarchguard.platform.governance;

/**
 * 已授权的 Platform Repository 尚无 GitHub 身份绑定，HTTP 层返回治理资源 404。
 * 报告提交也要求此绑定存在，以便校验提交者声明的外部仓库 ID。
 */
public class GithubLinkNotFoundException extends RuntimeException { }
