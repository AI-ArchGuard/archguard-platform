package io.github.aiarchguard.platform.project;

import java.util.UUID;

/**
 * Project 模块向 Web 等调用方开放的业务用例边界，返回公开快照并封装内部领域与存储模型。
 * 操作者统一取自可信身份上下文；成员参数中的 UUID 只指定管理目标，不能替代当前操作者。
 * 项目不存在或非成员不可见时返回未找到；可见成员缺少维护者角色时返回权限不足。
 */
public interface ProjectOperations {
    /**
     * 要求当前主体具有 project:create 权限，创建项目并将创建者加入为首位维护者。
     * key 按稳定标识规则严格校验且全局唯一，name 经规范化后保存；返回初始版本为 0 的项目快照。
     */
    ProjectView create(String key, String name);

    /**
     * 为当前项目成员读取快照；维护者和查看者均可读取。
     * 项目不存在或当前主体不是成员时统一抛出 {@link ProjectNotFoundException}。
     */
    ProjectView get(UUID projectId);

    /**
     * 仅分页列出当前主体加入的项目，按创建时间和项目 ID 倒序排列。
     * page 从 0 开始，size 为 1 至 100；total 只统计该主体可见的项目。
     */
    ProjectPage list(int page, int size);

    /**
     * 维护者以非负 expectedVersion 修改项目名称，成功后返回版本递增的快照，项目 key 保持不变。
     * 期望版本过时会抛出 {@link ProjectVersionConflictException}，让调用方重读当前状态再决定修改。
     */
    ProjectView update(UUID projectId, String name, long expectedVersion);

    /**
     * 维护者在版本匹配时物理删除项目及其成员关系，expectedVersion 必须非负。
     * 已装配的跨模块保护器会先检查阻止条件；有内容等阻止条件时抛出 {@link ProjectNotEmptyException}。
     * 检查与删除的并发边界见 {@link ProjectDeletionGuard}。
     */
    void delete(UUID projectId, long expectedVersion);

    /**
     * 项目成员分页查看同一项目的成员，按加入时间和主体 ID 升序排列。
     * page 从 0 开始且 size 为 1 至 100；非成员与项目不存在统一报告未找到。
     */
    ProjectMemberPage listMembers(UUID projectId, int page, int size);

    /**
     * 维护者为非空目标主体新增成员关系或替换其角色，返回变更后的成员快照；已有加入时间保留。
     * memberActorId 是管理目标，role 必须明确指定；将最后一名维护者降为查看者会被拒绝。
     */
    ProjectMemberView setMember(UUID projectId, UUID memberActorId, ProjectMemberRole role);

    /**
     * 维护者移除指定成员，但项目必须保留至少一名维护者。
     * 目标成员关系已不存在时抛出 {@link ProjectMemberNotFoundException}，不将重复移除视为成功。
     */
    void removeMember(UUID projectId, UUID memberActorId);
}
