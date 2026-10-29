package io.github.aiarchguard.platform.agentdocument;

import java.time.Instant;
import java.util.UUID;

/** 授权后读取的确切版本及正文；新上传不会改变该版本的身份、内容或摘要。 */
public record DocumentVersionView(UUID id, UUID documentId, UUID projectId, String documentKey,
                                  int versionNumber, String mediaType, String content, String contentSha256,
                                  int byteSize, int fragmentCount, UUID createdBy, Instant createdAt) {}
