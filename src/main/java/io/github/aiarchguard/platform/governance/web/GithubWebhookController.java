package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.governance.GithubGovernanceOperations;
import io.github.aiarchguard.platform.governance.GithubWebhookResult;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub Webhook 的 HTTP 接收适配层，以原始正文和签名交给应用接口完成 HMAC 验证。
 * 这不是普通当前用户的 Project 权限入口；仓库归属来自已登记的外部绑定，不能用客户端 UUID 自报。
 * Secret 缺失、签名错误和非法事件都由应用层失败关闭，控制器不自行建立 PR head 可信性。
 */
@RestController
@RequestMapping("/api/v1/github/webhooks")
final class GithubWebhookController {
    private final GithubGovernanceOperations github;
    GithubWebhookController(GithubGovernanceOperations github) { this.github = github; }

    /**
     * deliveryId 用于投递幂等，eventType 用于事件分派；payload 保留原始字节供验签，不先重排 JSON。
     * 时间窗口、允许动作、重放碰撞和单调 head 推进均在应用/存储层判断，响应不是门禁结果。
     */
    @PostMapping
    GithubWebhookResult receive(@RequestHeader("X-GitHub-Delivery") UUID deliveryId,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestBody byte[] payload) {
        return github.receive(deliveryId, eventType, signature, payload);
    }
}
