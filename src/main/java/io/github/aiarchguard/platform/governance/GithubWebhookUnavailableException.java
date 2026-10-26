package io.github.aiarchguard.platform.governance;

/**
 * Webhook 校验 Secret 未配置时的失败关闭信号，HTTP 层映射为 503。
 * 该异常不表示可以跳过 HMAC，也不触发外部凭据读取或自动补配。
 */
public class GithubWebhookUnavailableException extends RuntimeException { }
