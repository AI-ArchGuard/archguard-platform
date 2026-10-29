package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

/**
 * 例外的公开读取视图，把不可变创建、可选的追加式撤销及观察时刻状态合并供 API 展示。
 * 例外不会修改已保存 Finding 分类，门禁只在求值时对匹配的新增高风险问题使用它。
 *
 * @param id 创建例外的稳定 ID，也是初始版本 ID
 * @param projectId 所属项目
 * @param repositoryId 所属仓库
 * @param targetBranch 适用目标分支
 * @param ruleSetVersionId 适用规则版本，不自动跨规则版本继承
 * @param scopeType 单指纹或单规则匹配方式
 * @param scopeValue 对应 Platform 逻辑指纹或规则 ID
 * @param reason 创建原因
 * @param effectiveAt 含边界的生效时刻
 * @param expiresAt 不含边界的到期时刻
 * @param createdBy 创建主体
 * @param createdAt 创建时刻，历史求值不能使用尚未创建的例外
 * @param revokedBy 撤销主体，未撤销时为空
 * @param revokedAt 撤销生效时刻，未撤销时为空
 * @param revocationReason 独立保存的撤销原因，未撤销时为空
 * @param currentVersionId 当前记录版本：未撤销为 id，已存在撤销记录时为撤销版本 ID
 * @param version 当前实现的记录版本号：创建为 1，存在撤销记录为 2
 * @param status 按读取时刻派生的状态，不是对历史行的更新
 */
public record PolicyExceptionView(UUID id, UUID projectId, UUID repositoryId, String targetBranch,
                                  UUID ruleSetVersionId, PolicyExceptionScopeType scopeType, String scopeValue,
                                  String reason, Instant effectiveAt, Instant expiresAt, UUID createdBy,
                                  Instant createdAt, UUID revokedBy, Instant revokedAt, String revocationReason,
                                  UUID currentVersionId, int version, PolicyExceptionStatus status) { }
