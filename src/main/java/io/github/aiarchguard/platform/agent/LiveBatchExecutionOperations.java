package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** Project-authorized, non-mutating read of a context-policy batch's exact persisted request slots. */
public interface LiveBatchExecutionOperations {
    LiveBatchExecutionView get(UUID projectId, UUID batchId);
}
