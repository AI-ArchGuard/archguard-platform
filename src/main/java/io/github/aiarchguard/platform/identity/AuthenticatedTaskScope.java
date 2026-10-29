package io.github.aiarchguard.platform.identity;

import java.util.UUID;

/**
 * 将当前已验证的 OIDC 请求者延续到进程内的一次性后台任务。
 * 身份只能来自认证上下文；expectedRequester 用于比对，不能作为客户端自报身份。
 */
public interface AuthenticatedTaskScope {
    /** 捕获身份及到期时间，返回只能执行一次的任务；不把原始 Token 交给工作线程。 */
    Runnable capture(UUID expectedRequester, Runnable task);

    /** 开始新动作前重查捕获身份是否有效；已产生的费用结算不依赖续期后的发送许可。 */
    void requireActive();
}
