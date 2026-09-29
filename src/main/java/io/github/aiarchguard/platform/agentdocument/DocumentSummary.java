package io.github.aiarchguard.platform.agentdocument;

import java.time.Instant;
import java.util.UUID;

public record DocumentSummary(UUID id, String documentKey, int latestVersionNumber,
                              UUID createdBy, Instant createdAt) {}
