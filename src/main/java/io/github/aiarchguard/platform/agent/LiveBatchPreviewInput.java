package io.github.aiarchguard.platform.agent;

import java.util.List;
import java.util.UUID;

public record LiveBatchPreviewInput(List<UUID> templateRequestIds) {
    public LiveBatchPreviewInput { templateRequestIds = templateRequestIds == null ? null : List.copyOf(templateRequestIds); }
}
