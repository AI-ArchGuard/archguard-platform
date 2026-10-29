package io.github.aiarchguard.platform.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 仓库登记的公开只读快照，供 API 返回和扫描编排使用，不暴露 JDBC 或文件系统对象。
 *
 * @param id Platform 生成的仓库 UUID，与所属 projectId 一起确定资源归属
 * @param projectId 所属项目 UUID，本身不代表调用者已获授权
 * @param key 项目内唯一的业务标识，不是 Git 分支或版本
 * @param name 展示名称，创建时去除首尾空白
 * @param mountPath 受控源码根下的规范化相对路径，以正斜杠保存，不包含根目录绝对位置
 * @param scannerIdentity 由项目和仓库 UUID 组成的稳定来源标识，用于 Scanner 请求及报告归属校验
 * @param createdBy 来自可信当前身份的创建者 UUID
 * @param createdAt 使用应用时钟取得的创建时间
 * @param version 登记记录的版本值，当前创建为 0；不是源码提交、Scanner 或契约版本
 */
public record RepositoryView(UUID id, UUID projectId, String key, String name, String mountPath,
                             String scannerIdentity, UUID createdBy, Instant createdAt, long version) {
}
