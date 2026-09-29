package io.github.aiarchguard.platform.agentdocument;

import java.time.Instant;
import java.util.UUID;

public record DocumentVersionSummary(UUID id, int versionNumber, String mediaType, String contentSha256,
                                     int byteSize, int fragmentCount, UUID createdBy, Instant createdAt) {}
