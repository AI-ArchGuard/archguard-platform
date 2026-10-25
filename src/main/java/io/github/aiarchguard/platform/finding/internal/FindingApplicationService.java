package io.github.aiarchguard.platform.finding.internal;

import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.finding.DispositionView;
import io.github.aiarchguard.platform.finding.EvidenceView;
import io.github.aiarchguard.platform.finding.FindingDisposition;
import io.github.aiarchguard.platform.finding.FindingNotFoundException;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.finding.FindingVersionConflictException;
import io.github.aiarchguard.platform.finding.FindingView;
import io.github.aiarchguard.platform.finding.InvalidDispositionException;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Finding 公开用例的应用服务：先调用 Project 授权，再通过本模块存储查询或修改人工处置。
 * 状态和处置历史的事务由存储适配器承担，随后调用审计；本服务未声明覆盖二者的统一事务。
 */
@Service
final class FindingApplicationService implements FindingOperations {
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final FindingStore store;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final Clock clock;
    FindingApplicationService(ProjectAuthorization projects, CurrentActorProvider actors, FindingStore store,
                              AuditRecorder audit, TraceIdProvider traceIds, Clock clock) {
        this.projects=projects; this.actors=actors; this.store=store; this.audit=audit; this.traceIds=traceIds; this.clock=clock;
    }
    /** 先确认 Viewer 权限，再验证分页；未找到任务下的 Finding 时存储返回空列表。 */
    @Override public List<FindingView> list(UUID projectId, UUID jobId, int page, int size) {
        projects.requireViewer(projectId);
        if (page < 0 || size < 1 || size > 100) throw new InvalidDispositionException("Invalid page or size");
        return store.list(projectId, jobId, page, size);
    }
    /** 授权后按项目、任务和证据三个标识查询，资源不属于该范围时同样视为不存在。 */
    @Override public EvidenceView evidence(UUID projectId, UUID jobId, UUID evidenceId) {
        projects.requireViewer(projectId);
        return store.findEvidence(projectId, jobId, evidenceId).orElseThrow(FindingNotFoundException::new);
    }
    /** 供公开应用边界的调用方读取单条 Finding，不让存储层自行推断用户权限。 */
    @Override public FindingView get(UUID projectId, UUID jobId, UUID findingId) {
        projects.requireViewer(projectId);
        return store.find(projectId, jobId, findingId).orElseThrow(FindingNotFoundException::new);
    }
    /**
     * Maintainer 给出目标状态、原因和预期版本后，更新本次扫描的处置并追加历史。
     * 审计在存储更新成功后调用，最后重读视图；这里没有把更新、审计和重读包成一个事务。
     */
    @Override public FindingView disposition(UUID projectId, UUID jobId, UUID findingId, FindingDisposition next,
                                             String reason, long expectedVersion) {
        projects.requireMaintainer(projectId);
        if (next == null || reason == null || reason.isBlank() || reason.length() > 1000 || expectedVersion < 0) {
            throw new InvalidDispositionException("Disposition, reason, and a non-negative expected version are required");
        }
        // 先限定资源归属；previous 值取自此时的读取，真正写入是否成功仍取决于 expectedVersion。
        FindingView current = store.find(projectId, jobId, findingId).orElseThrow(FindingNotFoundException::new);
        UUID actor = actors.currentActor().id();
        // 操作者来自当前身份上下文，客户端不能在处置请求里指定历史记录的 actor。
        if (!store.updateDisposition(findingId, current.disposition(), next, expectedVersion, reason.trim(), actor,
                Instant.now(clock), UUID.randomUUID())) {
            throw new FindingVersionConflictException();
        }
        audit.record(new AuditEvent(actor, projectId, "finding.disposition", AuditResult.SUCCESS,
            traceIds.currentTraceId(), Map.of("findingId", findingId, "from", current.disposition(), "to", next)));
        return store.find(projectId, jobId, findingId).orElseThrow(FindingNotFoundException::new);
    }
    /** 历史存储仅按 Finding ID 查询，因此先在应用层确认它属于已授权的项目和任务。 */
    @Override public List<DispositionView> dispositionHistory(UUID projectId, UUID jobId, UUID findingId) {
        projects.requireViewer(projectId);
        store.find(projectId, jobId, findingId).orElseThrow(FindingNotFoundException::new);
        return store.history(findingId);
    }
}
