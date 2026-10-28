package io.github.aiarchguard.platform.agent.internal;

import io.github.aiarchguard.platform.agent.LiveBatchExecutionView;
import java.util.Optional;
import java.util.UUID;

/** 一次一致性读取的存储端口；实现只观察模板槽、请求、尝试和结算，不创建、领取、预留、结算或修补历史。 */
public interface LiveBatchExecutionStore {
    Optional<LiveBatchExecutionView> load(UUID projectId, UUID batchId);
}
