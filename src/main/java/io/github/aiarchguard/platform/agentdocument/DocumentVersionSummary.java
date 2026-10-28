package io.github.aiarchguard.platform.agentdocument;

import java.time.Instant;
import java.util.UUID;

/** 不含正文的版本摘要，用字节数、片段数及原始内容摘要描述不可变上传。 */
public record DocumentVersionSummary(UUID id, int versionNumber, String mediaType, String contentSha256,
                                     int byteSize, int fragmentCount, UUID createdBy, Instant createdAt) {}
