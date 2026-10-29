package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

/**
 * Platform Repository 与 GitHub 稳定数字身份的不可变绑定视图，供报告验收和 Webhook 路由使用。
 *
 * @param projectId Platform 项目
 * @param repositoryId Platform 仓库
 * @param provider 当前实现固定支持 github
 * @param providerRepositoryId 外部稳定数字仓库 ID，才是绑定及归属核验依据
 * @param ownerName 创建时登记的展示用 owner，不用于替代稳定 ID
 * @param repositoryName 创建时登记的展示用仓库名
 * @param createdBy 建立绑定的 Maintainer 主体
 * @param createdAt 绑定创建时间
 */
public record GithubRepositoryLinkView(UUID projectId, UUID repositoryId, String provider,
                                       String providerRepositoryId, String ownerName, String repositoryName,
                                       UUID createdBy, Instant createdAt) { }
