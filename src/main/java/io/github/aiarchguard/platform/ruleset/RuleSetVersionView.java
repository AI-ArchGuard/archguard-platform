package io.github.aiarchguard.platform.ruleset;

import java.time.Instant;
import java.util.UUID;

public record RuleSetVersionView(UUID id, UUID ruleSetId, int version, String yaml, String sha256,
                                 String scannerVersion, String schemaVersion, UUID createdBy, Instant createdAt) {
}
