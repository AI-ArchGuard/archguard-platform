package io.github.aiarchguard.platform.agent;

import java.util.UUID;

/** 按当前项目授权读取新上下文费用策略批次的精确持久化执行槽位；查询不预留费用或补发请求。 */
public interface LiveBatchExecutionOperations {
    LiveBatchExecutionView get(UUID projectId, UUID batchId);
}
