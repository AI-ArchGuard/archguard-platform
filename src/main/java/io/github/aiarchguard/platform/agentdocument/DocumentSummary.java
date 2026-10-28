package io.github.aiarchguard.platform.agentdocument;

import java.time.Instant;
import java.util.UUID;

/** 文档身份与最新版本号摘要；文档身份稳定，上传只追加版本。 */
public record DocumentSummary(UUID id, String documentKey, int latestVersionNumber,
                              UUID createdBy, Instant createdAt) {}
