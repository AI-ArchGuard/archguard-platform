package io.github.aiarchguard.platform.ruleset;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次规则提交的不可变版本快照，供管理 API、扫描和治理模块引用。
 * version 是规则集内的递增编号，id 是具体快照的标识；新提交追加记录而不覆盖旧 YAML。
 * sha256 来自 yaml 原文的 UTF-8 字节，不是规范化 YAML 的语义摘要；Scanner 和 Schema 版本用于追溯契约。
 */
public record RuleSetVersionView(UUID id, UUID ruleSetId, int version, String yaml, String sha256,
                                 String scannerVersion, String schemaVersion, UUID createdBy, Instant createdAt) {
}
