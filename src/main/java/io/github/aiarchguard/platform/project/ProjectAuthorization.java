package io.github.aiarchguard.platform.project;

import java.util.UUID;

/**
 * 其他业务模块使用的 Project 授权边界，避免调用方读取 Project 内部实现或成员表。
 * 不存在的项目与非成员不可见的项目统一报告未找到；已有可见性但角色不足才报告权限不足。
 */
public interface ProjectAuthorization {
    /**
     * 要求当前可信认证主体至少是项目成员；维护者和查看者均可通过，并返回授权快照。
     * 主体由身份模块取得，项目不存在或当前主体不是成员时抛出 {@link ProjectNotFoundException}。
     */
    ProjectAccessView requireViewer(UUID projectId);

    /**
     * 在成员可见性检查后要求当前主体为维护者。
     * 可见项目的查看者收到 {@link ProjectPermissionDeniedException}，对应 HTTP 403。
     */
    ProjectAccessView requireMaintainer(UUID projectId);

    /**
     * 为受信任的后台流程按指定主体重新检查当前成员关系，例如复核已保存请求的发起者。
     * 调用方负责从已验证身份或可信持久化事实取得 actorId；此参数不能用来信任客户端自报身份。
     * 项目不存在或该主体已无成员关系时，同样抛出 {@link ProjectNotFoundException}，对应 HTTP 404。
     */
    ProjectAccessView requireViewerForActor(UUID projectId, UUID actorId);
}
