package io.github.aiarchguard.platform.ruleset;

import java.time.Instant;
import java.util.UUID;

/**
 * 规则集的元数据视图，记录项目、仓库归属、仓库内唯一的 key、展示名称及创建信息。
 * 它不携带规则内容；实际扫描使用单独追加并按版本 ID 引用的 {@link RuleSetVersionView}。
 */
public record RuleSetView(UUID id, UUID projectId, UUID repositoryId, String key, String name,
                          UUID createdBy, Instant createdAt) {
}
