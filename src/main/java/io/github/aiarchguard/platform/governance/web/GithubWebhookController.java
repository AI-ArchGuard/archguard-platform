package io.github.aiarchguard.platform.governance.web;

import io.github.aiarchguard.platform.governance.GithubGovernanceOperations;
import io.github.aiarchguard.platform.governance.GithubWebhookResult;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/github/webhooks")
final class GithubWebhookController {
    private final GithubGovernanceOperations github;
    GithubWebhookController(GithubGovernanceOperations github) { this.github = github; }

    @PostMapping
    GithubWebhookResult receive(@RequestHeader("X-GitHub-Delivery") UUID deliveryId,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader("X-Hub-Signature-256") String signature,
            @RequestBody byte[] payload) {
        return github.receive(deliveryId, eventType, signature, payload);
    }
}
