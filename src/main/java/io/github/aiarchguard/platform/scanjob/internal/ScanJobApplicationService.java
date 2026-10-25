package io.github.aiarchguard.platform.scanjob.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.repository.RepositoryOperations;
import io.github.aiarchguard.platform.ruleset.RuleSetCatalog;
import io.github.aiarchguard.platform.scanjob.InvalidScanJobException;
import io.github.aiarchguard.platform.scanjob.ScanJobConflictException;
import io.github.aiarchguard.platform.scanjob.ScanJobNotFoundException;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobResult;
import io.github.aiarchguard.platform.scanjob.ScanJobStatus;
import io.github.aiarchguard.platform.scanjob.ScanJobView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 扫描任务应用服务：在当前操作者的项目权限下提交、查询、取消和下载结果。
 * 通过公开 Repository/RuleSet 接口核对输入，任务事实由存储保存，执行交给后台邮箱协调器。
 * 本类没有整体事务声明；任务写入、取消信号和审计不是一个原子操作。
 */
@Service
final class ScanJobApplicationService implements ScanJobOperations {
    private final ProjectAuthorization projects;
    private final RepositoryOperations repositories;
    private final RuleSetCatalog ruleSets;
    private final CurrentActorProvider actors;
    private final ScanJobStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;
    private final RunnerCancellationPort cancellations;

    ScanJobApplicationService(ProjectAuthorization projects, RepositoryOperations repositories, RuleSetCatalog ruleSets,
                              CurrentActorProvider actors, ScanJobStore store, AuditRecorder audit,
                              TraceIdProvider traceIds, Clock clock, RunnerCancellationPort cancellations) {
        this.projects = projects; this.repositories = repositories; this.ruleSets = ruleSets; this.actors = actors;
        this.store = store; this.audit = audit; this.traceIds = traceIds; this.clock = clock; this.cancellations=cancellations;
    }

    /**
     * 先确认 Maintainer、仓库及规则版本归属，再以项目范围幂等键创建 QUEUED 任务。
     * 请求摘要只由仓库 ID 和规则版本 ID 组成；同键重试必须匹配这两个输入。
     */
    @Override
    public ScanJobView submit(UUID projectId, UUID repositoryId, UUID versionId, String idempotencyKey) {
        projects.requireMaintainer(projectId);
        repositories.get(projectId, repositoryId);
        ruleSets.requireVersion(projectId, repositoryId, versionId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new InvalidScanJobException("Idempotency-Key must contain between 1 and 128 characters");
        }
        String requestHash = sha256((repositoryId + "\n" + versionId).getBytes(StandardCharsets.UTF_8));
        var existing = store.findByIdempotency(projectId, idempotencyKey);
        if (existing.isPresent()) {
            // 资源仍会先重新授权；幂等命中不会省略本次调用的访问检查。
            if (!existing.get().requestSha256().equals(requestHash)) {
                throw new ScanJobConflictException("Idempotency-Key was already used for a different request");
            }
            return view(existing.get());
        }
        UUID actorId = actors.currentActor().id();
        ScanJobRecord job = new ScanJobRecord(UUID.randomUUID(), projectId, repositoryId, versionId,
            idempotencyKey, requestHash, ScanJobStatus.QUEUED, null, actorId, Instant.now(clock), null, null,
            null, 0, null, 0, null, null, null, null, false, null, null);
        try {
            store.insert(job);
        } catch (DuplicateKeyException exception) {
            // 并发请求可能同时查不到旧记录；唯一约束决定胜者，失败者重读后仍须比对摘要。
            ScanJobRecord raced = store.findByIdempotency(projectId, idempotencyKey).orElseThrow();
            if (!raced.requestSha256().equals(requestHash)) {
                throw new ScanJobConflictException("Idempotency-Key was already used for a different request");
            }
            return view(raced);
        }
        record(actorId, projectId, "scanjob.submit", Map.of("jobId", job.id()));
        return view(job);
    }

    /** Viewer 授权后同时按项目和任务 ID 查询，避免把全局任务 ID 当成访问凭证。 */
    @Override public ScanJobView get(UUID projectId, UUID jobId) {
        projects.requireViewer(projectId);
        return view(store.find(projectId, jobId).orElseThrow(ScanJobNotFoundException::new));
    }
    /** 校验分页上界后读取当前项目的元数据列表，排序与偏移由存储适配器负责。 */
    @Override public List<ScanJobView> list(UUID projectId, int page, int size) {
        projects.requireViewer(projectId);
        if (page < 0 || size < 1 || size > 100) throw new InvalidScanJobException("Invalid page or size");
        return store.list(projectId, page, size).stream().map(ScanJobApplicationService::view).toList();
    }
    /**
     * 先持久化取消意图；只有仍处于 CANCEL_REQUESTED 时才向当前 attempt 投递尽力取消信号。
     * 排队任务直接成为 CANCELLED；运行任务此时尚未确认退出，终态任务则保持原状态。
     */
    @Override public ScanJobView cancel(UUID projectId, UUID jobId) {
        projects.requireMaintainer(projectId);
        ScanJobRecord job = store.cancel(projectId, jobId, Instant.now(clock)).orElseThrow(ScanJobNotFoundException::new);
        if(job.status()==ScanJobStatus.CANCEL_REQUESTED)cancellations.request(job.id(),job.attemptToken());
        record(actors.currentActor().id(), projectId, "scanjob.cancel", Map.of("jobId", jobId, "status", job.status()));
        return view(job);
    }
    /** 仅暴露 SUCCEEDED 且已保存报告的结果；FAILED 的部分输出不满足此入口的条件。 */
    @Override public ScanJobResult result(UUID projectId, UUID jobId) {
        projects.requireViewer(projectId);
        ScanJobRecord job = store.find(projectId, jobId).orElseThrow(ScanJobNotFoundException::new);
        if (job.status() != ScanJobStatus.SUCCEEDED || job.report() == null) {
            throw new ScanJobConflictException("A successful result is not available for this job");
        }
        return new ScanJobResult(job.report(), job.reportSha256(), job.scannerVersion(), job.schemaVersion());
    }

    /** 记录本次成功操作与 traceId；此调用没有将前面的存储写入纳入同一事务。 */
    private void record(UUID actor, UUID project, String action, Map<String, Object> metadata) {
        audit.record(new AuditEvent(actor, project, action, AuditResult.SUCCESS, traceIds.currentTraceId(), metadata));
    }
    /** 从内部完整记录提取公开元数据，不泄露租约、token 或报告正文。 */
    private static ScanJobView view(ScanJobRecord j) {
        return new ScanJobView(j.id(), j.projectId(), j.repositoryId(), j.ruleSetVersionId(), j.status(), j.outcome(),
            j.createdBy(), j.createdAt(), j.startedAt(), j.completedAt(), j.attempt(), j.version(),
            j.failureCode(), j.failureMessage(), j.reportSha256());
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
