package io.github.aiarchguard.platform.governance;

/**
 * Webhook 投递的处理处置，不是门禁结果：RECEIVED 为中间接收态，APPLIED 表示已应用 PR 更新，
 * STALE 表示超出窗口或未通过顺序推进，IGNORED 表示非允许事件/动作，UNLINKED 表示未找到仓库绑定。
 * 即使投递已合法验签，也只有 APPLIED 会推进当前 PR 事实。
 */
public enum GithubWebhookDisposition { RECEIVED, APPLIED, STALE, IGNORED, UNLINKED }
