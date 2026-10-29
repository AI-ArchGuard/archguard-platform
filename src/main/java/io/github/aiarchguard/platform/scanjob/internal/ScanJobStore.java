package io.github.aiarchguard.platform.scanjob.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ScanJob 内部持久化端口，封装任务查询、认领以及受状态/token 条件保护的更新。
 * 不承担用户授权；应用服务先授权，后台协调器则在可信内部流程中使用它。
 * 各方法的局部事务和条件更新不等于任务、文件邮箱及 Finding 存储之间存在整体事务。
 */
public interface ScanJobStore {
    /** 插入任务；项目范围的幂等唯一约束交给数据库裁决并发重复。 */
    void insert(ScanJobRecord job);
    /** 幂等键只在同一项目内匹配，调用方再比较请求摘要以辨别同键异义。 */
    Optional<ScanJobRecord> findByIdempotency(UUID projectId, String key);
    /** 面向已授权应用流程的项目范围查询。 */
    Optional<ScanJobRecord> find(UUID projectId, UUID jobId);
    /** 供后台结果关联使用的全局 ID 查询，本方法不执行当前用户授权。 */
    Optional<ScanJobRecord> findById(UUID jobId);
    /** 按创建时间和 ID 倒序分页；页码和大小须由调用方预先校验。 */
    List<ScanJobRecord> list(UUID projectId, int page, int size);
    /** QUEUED 直接取消，RUNNING 转为取消请求；其他状态保持原状，任务不存在时返回空。 */
    Optional<ScanJobRecord> cancel(UUID projectId, UUID jobId, Instant now);
    /**
     * 在尝试次数未耗尽时认领一个 QUEUED 或租约已过期的 RUNNING，并设置新的 token/租约。
     * 当前实现不认领 CANCEL_REQUESTED，也不在此终结次数耗尽的任务。
     */
    Optional<ScanJobRecord> claim(Instant now, Instant leaseUntil, UUID attemptToken, int maxAttempts);
    /** 仅当前 token 对应的 RUNNING 可以续租；当前邮箱协调器没有调用此续租入口。 */
    boolean heartbeat(UUID jobId, UUID attemptToken, Instant leaseUntil);
    /**
     * 仅退出码 0/2 且当前 token/状态匹配时更新；取消请求优先完成为 CANCELLED，否则为成功和对应结论。
     * 返回 false 表示未更新，不提供与先前 Finding 写入的一体化回滚。
     */
    boolean complete(UUID jobId, UUID attemptToken, int exitCode, byte[] report, String sha256,
                     boolean partial, String scannerVersion, String schemaVersion, Instant now);
    /** 只将 QUEUED 且带匹配 ci:jobId 键的导入记录完成，不经过 Runner attempt。 */
    boolean completeImported(UUID jobId, byte[] report, String sha256, String scannerVersion,
                             String schemaVersion, io.github.aiarchguard.platform.scanjob.ScanJobOutcome outcome,
                             Instant now);
    /** 当前 attempt 失败时可保存部分报告；若已请求取消，则成为 CANCELLED 并清除报告/失败信息。 */
    boolean fail(UUID jobId, UUID attemptToken, String code, String message, byte[] partialReport,
                 String sha256, Instant now);
    /** 删除保护只需确认是否存在任何任务，包括终态任务。 */
    boolean existsForProject(UUID projectId);
}
