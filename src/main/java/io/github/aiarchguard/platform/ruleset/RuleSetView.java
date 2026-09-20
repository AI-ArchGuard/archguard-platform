package io.github.aiarchguard.platform.ruleset;

import java.time.Instant;
import java.util.UUID;

public record RuleSetView(UUID id, UUID projectId, UUID repositoryId, String key, String name,
                          UUID createdBy, Instant createdAt) {
}
