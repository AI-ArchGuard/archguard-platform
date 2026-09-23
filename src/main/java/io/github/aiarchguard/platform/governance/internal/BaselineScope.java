package io.github.aiarchguard.platform.governance.internal;

import java.util.UUID;

public record BaselineScope(UUID id, UUID projectId, UUID repositoryId, String targetBranch, UUID ruleSetVersionId,
                            UUID activeVersionId, long nextVersion, long selectionVersion) { }
