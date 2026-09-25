package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

public record BaselineVersionView(UUID id, UUID projectId, UUID repositoryId, String targetBranch,
                                  UUID ruleSetVersionId, long version, UUID scanJobId, String commitSha,
                                  String reportSha256, String fingerprintVersion, UUID createdBy,
                                  Instant createdAt, boolean active, long selectionVersion) { }
