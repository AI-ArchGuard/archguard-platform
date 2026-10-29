package io.github.aiarchguard.platform.finding;

import java.time.Instant;
import java.util.UUID;

/**
 * 某个扫描 Finding 的一次人工处置历史，供查询接口展示前后状态、原因、操作者与变更后版本。
 * 这是 Finding 模块的追加历史；外部审计记录由应用服务另行写入。
 */
public record DispositionView(UUID id, UUID findingId, FindingDisposition previousDisposition,
                              FindingDisposition newDisposition, String reason, UUID actorId,
                              Instant occurredAt, long version) {
}
