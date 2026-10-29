package io.github.aiarchguard.platform.governance;

/**
 * Webhook 的签名、正文大小、事件类型或 JSON 等接收校验失败时使用的应用异常。
 * HTTP 层统一返回固定的 401 文案，不把内部原因、原始正文或签名回显给调用方。
 */
public class InvalidGithubWebhookException extends RuntimeException {
    public InvalidGithubWebhookException(String message) { super(message); }
}
