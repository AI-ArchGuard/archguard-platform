package io.github.aiarchguard.platform.agentdocument;

import java.util.UUID;

public record DocumentFragmentView(UUID projectId, UUID documentVersionId, String contentSha256,
                                   int fragmentIndex, int startOffset, String fragmentSha256, String content) {}
