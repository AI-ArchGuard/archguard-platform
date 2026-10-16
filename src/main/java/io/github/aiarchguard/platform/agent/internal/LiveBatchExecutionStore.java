package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.LiveBatchExecutionView;
import java.util.Optional;
import java.util.UUID;

/** One read snapshot; implementations must not allocate, claim, reserve, settle or repair history. */
public interface LiveBatchExecutionStore {
    Optional<LiveBatchExecutionView> load(UUID projectId, UUID batchId);
}
