package io.github.aiarchguard.platform.governance;

import java.time.Instant;
import java.util.UUID;

/**
 * 公开基线视图：不可变版本事实与读取时的活动选择状态放在同一响应中，供 API 和门禁追溯。
 * active、selectionVersion 来自当前比较空间，切换选择不会改变其他版本字段。
 *
 * @param id 不可变基线版本 ID，历史比较和门禁引用这个 ID
 * @param projectId 比较空间所属项目
 * @param repositoryId 比较空间所属仓库
 * @param targetBranch 不带 refs/heads/ 前缀的目标分支
 * @param ruleSetVersionId 隔离比较语义的不可变规则版本
 * @param version 本空间内的基线内容版本号，与活动选择版本号分开
 * @param scanJobId 生成快照的成功扫描任务
 * @param commitSha 提升时绑定的提交 SHA，不代表 Platform 证明了 Git 祖先关系
 * @param reportSha256 被接受报告原始字节的摘要
 * @param fingerprintVersion 快照采用的逻辑指纹算法版本
 * @param createdBy 创建该版本的主体
 * @param createdAt 版本创建时间
 * @param active 读取时该版本是否被本空间选中
 * @param selectionVersion 本空间当前的活动选择版本号，不属于基线内容
 */
public record BaselineVersionView(UUID id, UUID projectId, UUID repositoryId, String targetBranch,
                                  UUID ruleSetVersionId, long version, UUID scanJobId, String commitSha,
                                  String reportSha256, String fingerprintVersion, UUID createdBy,
                                  Instant createdAt, boolean active, long selectionVersion) { }
