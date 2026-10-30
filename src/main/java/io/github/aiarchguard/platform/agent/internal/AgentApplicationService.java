package io.github.aiarchguard.platform.agent.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiarchguard.platform.agent.AgentConflictException;
import io.github.aiarchguard.platform.agent.AgentInvalidException;
import io.github.aiarchguard.platform.agent.AgentModelPort;
import io.github.aiarchguard.platform.agent.AgentNotFoundException;
import io.github.aiarchguard.platform.agent.AgentOperations;
import io.github.aiarchguard.platform.agent.AgentRequestView;
import io.github.aiarchguard.platform.agent.CreateAgentRequest;
import io.github.aiarchguard.platform.agentdocument.DocumentOperations;
import io.github.aiarchguard.platform.agentdocument.DocumentVersionView;
import io.github.aiarchguard.platform.audit.AuditEvent;
import io.github.aiarchguard.platform.audit.AuditRecorder;
import io.github.aiarchguard.platform.audit.AuditResult;
import io.github.aiarchguard.platform.common.TraceIdProvider;
import io.github.aiarchguard.platform.finding.FindingOperations;
import io.github.aiarchguard.platform.finding.FindingView;
import io.github.aiarchguard.platform.governance.ReportSubmissionOperations;
import io.github.aiarchguard.platform.identity.CurrentActorProvider;
import io.github.aiarchguard.platform.project.ProjectAuthorization;
import io.github.aiarchguard.platform.scanjob.ScanJobOperations;
import io.github.aiarchguard.platform.scanjob.ScanJobView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 原合成解释/摘要的应用编排：通过公开领域接口取获授权事实，生成随机引用别名和最小输入，在事务中保存幂等请求及审计，提交成功后才交给 worker。
 */
@Service
class AgentApplicationService implements AgentOperations {
    private final ProjectAuthorization projects;
    private final CurrentActorProvider actors;
    private final AgentStore store;
    private final AgentSchemaAvailability schema;
    private final AgentModelPort model;
    private final AgentWorker worker;
    private final Executor executor;
    private final AuditRecorder audit;
    private final TraceIdProvider traceIds;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean enabled;
    private final AuthorizedAgentSnapshotBuilder snapshots;

    AgentApplicationService(ProjectAuthorization projects, CurrentActorProvider actors, ScanJobOperations jobs,
            ReportSubmissionOperations reports,
            FindingOperations findings, DocumentOperations documents, AgentStore store,
            AgentSchemaAvailability schema, AgentModelPort model,
            AgentWorker worker, @Qualifier("agentTaskExecutor") Executor executor, AuditRecorder audit,
            TraceIdProvider traceIds, ObjectMapper mapper, Clock clock,
            @Value("${archguard.agent.enabled:false}") boolean enabled) {
        this.projects = projects; this.actors = actors; this.store = store; this.schema = schema;
        this.model = model; this.worker = worker;
        this.executor = executor; this.audit = audit; this.traceIds = traceIds; this.mapper = mapper;
        this.clock = clock; this.enabled = enabled;
        this.snapshots = new AuthorizedAgentSnapshotBuilder(jobs, reports, findings, documents, mapper);
    }

    /**
     * 在同一事务中授权资源、构建快照、按输入摘要处理幂等并写审计；
     * 仅 afterCommit 排队，避免 worker 看不到尚未提交的请求。
     */
    @Override
    @Transactional
    public AgentRequestView create(UUID projectId, String key, CreateAgentRequest request) {
        projects.requireViewer(projectId);
        schema.requireReady();
        validate(key, request);
        UUID requester = actors.currentActor().id();
        var selection = snapshots.authorize(projectId, request);
        ScanJobView scan = selection.scan();
        List<UUID> selectedIds = selection.findings().stream().map(FindingView::id).toList();
        List<DocumentVersionView> versions = selection.versions();
        String digest = snapshots.legacyDigest(selection);
        var existing = store.findByKey(projectId, requester, key);
        if (existing.isPresent()) {
            if (!existing.get().view().bindings().inputDigest().equals(digest)) throw new AgentConflictException();
            return existing.get().view();
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String trace = traceIds.currentTraceId();
        AgentSnapshot projected = snapshots.build(selection,
            new AuthorizedAgentSnapshotBuilder.SnapshotIdentity(id, requester, trace, now),
            AuthorizedAgentSnapshotBuilder.LEGACY, digest);
        AgentModelPort.ModelInput input = projected.input();
        int conservativeTokens = json(input).getBytes(StandardCharsets.UTF_8).length;
        String failureCode = !enabled ? "MODEL_DISABLED" : !model.syntheticOnly() || !model.available() ? "MODEL_UNAVAILABLE"
            : conservativeTokens > 8000 ? "QUOTA_EXHAUSTED" : null;
        AgentRequestView.AgentFailure failure = failureCode == null ? null
            : new AgentRequestView.AgentFailure(failureCode, message(failureCode));
        AgentRequestView.VersionBindings bindings = projected.view().bindings();
        AgentRequestView view = new AgentRequestView(id, projectId, requester, trace,
            request.purpose(), failure == null ? "QUEUED" : "FAILED", bindings, null, failure,
            null, now, now);
        AgentSnapshot snapshot = new AgentSnapshot(view, input, projected.candidates());
        if (!store.insert(snapshot, key)) {
            AgentRequestView replay = store.findByKey(projectId, requester, key).orElseThrow().view();
            if (!replay.bindings().inputDigest().equals(digest)) throw new AgentConflictException();
            return replay;
        }
        audit.record(new AuditEvent(requester, projectId, "agent.request.created",
            failure == null ? AuditResult.SUCCESS : AuditResult.DENIED, trace,
            Map.of("requestId", id, "state", view.state(), "inputDigest", digest,
                "failureCode", failureCode == null ? "NONE" : failureCode,
                "scanJobId", scan.id(), "reportSha256", scan.reportSha256(), "findingIds", selectedIds,
                "documentVersionIds", versions.stream().map(DocumentVersionView::id).toList())));
        if (failure == null) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { executor.execute(() -> worker.process(projectId, id)); }
                catch (RuntimeException rejected) { worker.failQueued(projectId, id, "MODEL_UNAVAILABLE"); }
            }
        });
        return view;
    }

    /** 读取重新校验当前 Viewer；历史请求存在也不能绕过已经撤销的项目权限。 */
    @Override
    @Transactional(readOnly = true)
    public AgentRequestView get(UUID projectId, UUID requestId) {
        projects.requireViewer(projectId);
        schema.requireReady();
        return store.find(projectId, requestId).map(AgentSnapshot::view).orElseThrow(AgentNotFoundException::new);
    }

    /** 区分单 Finding 解释和多 Finding PR 摘要的选择约束，同时限制幂等键、数量与重复 ID。 */
    private static void validate(String key, CreateAgentRequest request) {
        boolean explanation = request != null && "FINDING_EXPLANATION".equals(request.purpose());
        boolean summary = request != null && "PR_SUMMARY".equals(request.purpose());
        if (key == null || key.isBlank() || key.length() > 128
                || key.chars().anyMatch(c -> c < 33 || c > 126)
                || (!explanation && !summary)
                || request.scanJobId() == null || request.reportSha256() == null
                || !request.reportSha256().matches("[a-f0-9]{64}")
                || (explanation && request.prHeadRevisionId() != null)
                || (summary && request.prHeadRevisionId() == null)
                || request.findingIds() == null || request.findingIds().isEmpty()
                || request.findingIds().size() > (summary ? 20 : 1)
                || request.findingIds().contains(null)
                || new HashSet<>(request.findingIds()).size() != request.findingIds().size()
                || request.documentVersionIds() == null
                || request.documentVersionIds().size() > 10 || request.documentVersionIds().contains(null)
                || new HashSet<>(request.documentVersionIds()).size() != request.documentVersionIds().size()) {
            throw new AgentInvalidException();
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent input encoding failed", exception); }
    }

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static String alias() { return "c-" + UUID.randomUUID().toString(); }

    static String message(String code) { return switch (code) {
        case "MODEL_DISABLED" -> "Agent model calls are disabled.";
        case "MODEL_UNAVAILABLE" -> "Agent model is unavailable.";
        case "QUOTA_EXHAUSTED" -> "Agent request exceeds its configured limit.";
        case "MODEL_TIMEOUT" -> "Agent model timed out.";
        case "OUTPUT_INVALID" -> "Agent output did not pass validation.";
        case "CITATION_INVALID" -> "Agent citation did not pass validation.";
        case "AUTHORIZATION_REVOKED" -> "Project authorization was revoked.";
        default -> "Agent request failed.";
    }; }
}
