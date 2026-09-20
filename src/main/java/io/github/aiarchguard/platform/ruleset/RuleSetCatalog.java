package io.github.aiarchguard.platform.ruleset;

import java.util.UUID;

public interface RuleSetCatalog {
    RuleSetVersionView requireVersion(UUID projectId, UUID repositoryId, UUID versionId);
}
