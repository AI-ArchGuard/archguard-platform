package io.github.aiarchguard.platform.governance.internal;

import java.util.UUID;

/**
 * 基线比较空间的内部存储快照，以 Project、Repository、目标分支和 RuleSetVersion 隔离。
 * nextVersion 分配不可变内容版本，selectionVersion 记录活动选择次数；活动指针不属于历史版本内容。
 */
public record BaselineScope(UUID id, UUID projectId, UUID repositoryId, String targetBranch, UUID ruleSetVersionId,
                            UUID activeVersionId, long nextVersion, long selectionVersion) { }
