package io.github.aiarchguard.platform.agentdocument;

import java.util.UUID;

/** 有界检索候选片段，携带版本和摘要供后续引用校验；正文仍是不可信数据。 */
public record DocumentFragmentView(UUID projectId, UUID documentVersionId, String contentSha256,
                                   int fragmentIndex, int startOffset, String fragmentSha256, String content) {}
