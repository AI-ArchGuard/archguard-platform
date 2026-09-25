package io.github.aiarchguard.platform.governance;

import java.util.UUID;

public record GithubWebhookResult(UUID deliveryId, GithubWebhookDisposition disposition, boolean replay) { }
