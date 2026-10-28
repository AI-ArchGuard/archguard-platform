package io.github.aiarchguard.platform.identity;

/** 后台认证任务缺失、过期或已被消费时的固定失败类型，不携带 Token 或身份详情。 */
public final class InactiveAuthenticatedTaskException extends IllegalStateException {
    public InactiveAuthenticatedTaskException() {
        super("An active authenticated OIDC task is required");
    }
}
