package io.github.aiarchguard.platform.agentdocument;

import java.time.Instant;
import java.util.UUID;

public record DocumentVersionView(UUID id, UUID documentId, UUID projectId, String documentKey,
                                  int versionNumber, String mediaType, String content, String contentSha256,
                                  int byteSize, int fragmentCount, UUID createdBy, Instant createdAt) {}
